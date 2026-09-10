# Phase 0 Research: Shared Seed Personas & Baseline Dataset

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Date**: 2026-09-07

Six unknowns blocked the design. All are resolved below. Each finding is grounded in what the codebase actually does today, not in what a Spring application typically does.

---

## R1 — How does an environment declare itself "production"?

**Decision**: Two independent guards, neither depending on the other.

1. **Opt-in flag**: `app.seed.personas.enabled`, default `false`. The seed runner is annotated `@ConditionalOnProperty(name = "app.seed.personas.enabled", havingValue = "true")`, so with the flag absent the bean is never created and no seeding code runs at all.
2. **Production refusal**: when the flag *is* true, `SeedGuard` still refuses if any active Spring profile is `prod` or `production`, or if `app.seed.personas.environment` is set to `production`. Refusal throws at startup rather than warning and continuing.

**Rationale**: The codebase had **no `@Profile` annotation anywhere** — a grep across `backend/src/main` returns nothing — so there is no existing profile convention to inherit or accidentally contradict. That absence is the finding: a profile-only guard would rest on a convention this project has not yet established, and would silently pass in every environment because no profile is ever set. The flag is therefore the primary guard and carries the safe default; the profile check is the backstop that catches a production deployment where someone set the flag by mistake.

Refusing loudly rather than skipping quietly is what SCR-002 requires: an operator must be able to tell "seeding was prevented" from "seeding is broken". A silent skip produces an environment with no personas, which reads identically to a seeding bug.

**Alternatives considered**:
- *Profile-only guard (`@Profile("!prod")`)*: rejected. With no profiles set anywhere today, `!prod` is true in production too. It would look like a guard and guard nothing.
- *Datasource-URL sniffing (refuse if not H2)*: rejected. Couples seeding to a storage choice, and that choice has since changed — the primary datasource is now PostgreSQL, which would have made such a guard refuse every legitimate environment.
- *Single combined guard*: rejected during clarification in favour of defense in depth; two independent mechanisms mean one misconfiguration is not sufficient to seed production.

---

## R2 — Where must the sparse persona sit to trigger *both* insufficient-data paths?

**Decision**: Two SUCCESS DEBIT transactions, both dated 10 days before the seeding moment. No savings goal.

**Rationale**: The two thresholds are configured independently and measured differently, and the sparse persona must fail both with margin.

| Consumer | Rule | Source |
|---|---|---|
| Risk Scoring | Earliest transaction must be **older than 3 months** or the result is `INSUFFICIENT_DATA` | `minMonths: 3` in [risk-score-rules.yaml](backend/src/main/resources/risk-score-rules.yaml), applied in [RiskScoreService.java:208-220](backend/src/main/java/com/group1/banking/service/impl/RiskScoreService.java#L208-L220) |
| Chatbot | Needs **≥ 3** SUCCESS DEBIT/TRANSFER transactions within a **30-day** lookback or `sufficientData` is false | `app.chatbot.transactions.min-for-personalization=3`, `lookback-days=30`, applied in [SavingsChatContextService.java:69-97](backend/src/main/java/com/group1/banking/service/impl/SavingsChatContextService.java#L69-L97) |

Two transactions at 10 days old clears both simultaneously: the earliest is ~10 days old against a 3-month requirement (nowhere near the boundary), and the count is 2 against a minimum of 3 (one short, and deliberately not zero — an empty account would also pass but would exercise a trivially-empty path rather than a genuinely-sparse one).

The choice of *two* rather than zero matters. Zero transactions makes both checks pass for the wrong reason and would hide a regression where the chatbot's count comparison flipped from `>=` to `>`. Two is the largest value that still fails the chatbot threshold, so it is the tightest meaningful test of that specific comparison.

**Alternatives considered**:
- *Zero transactions*: rejected as above — passes vacuously, tests less.
- *Transactions old enough to fail only the chatbot*: rejected, would give risk scoring a score and break FR-007's requirement that one persona exercises both paths.
- *Separate personas per feature*: rejected outright; it is the duplication FR-008 exists to prevent.

---

## R3 — Where can the salaried and goal-saver personas sit without landing on a band boundary?

**Decision**: Salaried persona — 18 months of history ending the day before seeding, with a monthly credit and 4–6 categorised debits per month. Goal saver — a goal at **45%** progress with a target date 90 days out.

**Rationale**: Both risk factors are banded, and FR-023 forbids sitting on a boundary. The bands are:

- **Risk level** cut points at 25 / 45 / 70 ([risk-score-rules.yaml](backend/src/main/resources/risk-score-rules.yaml))
- **`SPENDING_INCOME_RATIO`** (weight 0.5) bands at 0.5 / 0.7 / 0.9 / 1.0
- **`SAVING_BALANCE`** (weight 0.35), measured in months of spend covered, bands at 0.25 / 1 / 3 / 6
- **`GOAL_PROGRESS`** (weight 0.15) bands at 10 / 30 / 60 / 90 / 99.9

45% goal progress sits squarely inside the 30–60 band ("on track"), 15 points clear on either side — comfortably the widest margin available in that factor. A spending-to-income ratio near 0.6 sits mid-band between 0.5 and 0.7. 18 months of history is 6× the 3-month risk minimum, so the salaried persona cannot drift into insufficiency even if `minMonths` were later raised to 12.

The composite score these produce must itself be checked against the 25/45/70 level boundaries. That check is a task, not an assumption: the catalogue records the expected level, and `PersonaCatalogueValidationTest` fails if the computed score lands within a small margin of a cut point. This is deliberately a test rather than a hand calculation, because the weights live in a config file that can change.

**Alternatives considered**:
- *Pinning an exact expected numeric score*: rejected. It would couple the catalogue to the full weighting formula, so any rules tuning breaks every persona at once rather than surfacing one clear failure.
- *Progress at 50%*: rejected, marginally — 45% is equidistant-ish within its band and avoids the round number that invites someone to "tidy" it to 50 and land nearer a boundary.

---

## R4 — How are dates anchored so classification never drifts?

**Decision**: The catalogue expresses every date as a **signed offset from the seeding moment** (`daysAgo`, `monthsAgo`, `daysAhead`), and `PersonaDateAnchor` resolves those offsets against a single `Instant` captured once per seed or reset run.

**Rationale**: Risk scoring computes its window as `LocalDate.now(UTC).minusMonths(minMonths)` on every call ([RiskScoreService.java:216-218](backend/src/main/java/com/group1/banking/service/impl/RiskScoreService.java#L216-L218)), and the chatbot computes `Instant.now().minus(lookbackDays, DAYS)` on every call. Both windows therefore move continuously. Fixed calendar dates in the catalogue would be overtaken by both, silently reclassifying the salaried persona as `INSUFFICIENT_DATA` and the sparse persona's transactions out of the chatbot's lookback — the failure SC-008 exists to prevent.

Capturing the anchor **once per run** rather than calling `Instant.now()` per row matters: a seed that spans a midnight boundary would otherwise produce a persona whose transactions straddle two different "today"s, making the run non-reproducible in exactly the way FR-009 forbids.

**Alternatives considered**:
- *Fixed absolute dates*: rejected during clarification for the drift reason above.
- *A pinnable "as of" clock injected everywhere*: rejected as disproportionate. It would give byte-identical timestamps but requires threading a `Clock` through `RiskScoreService` and `SavingsChatContextService`, changing production code to serve test data. The spec explicitly accepts non-identical literal timestamps (FR-011) in exchange for not doing this.

---

## R5 — How does the seeder assign account identifiers without colliding?

**Decision**: The seeder reuses the application's existing scheme — `accountId = accountRepository.count() + 1000` and `accountNumber = String.format("ACC%010d", accountId)` — extracted into a small shared helper so seeding and `AccountService` cannot drift apart.

**Rationale**: `Account.accountId` carries **no `@GeneratedValue`** — it is assigned by hand in [AccountService.java:490-496](backend/src/main/java/com/group1/banking/service/impl/AccountService.java#L490-L496). A seeder that inserted rows with its own identifiers (a reserved high range, say) would work until `AccountService` next created an account and computed `count() + 1000` into a value the seed had already taken.

This is worth stating plainly: **`count() + 1000` is pre-existing fragile behaviour**, not something this feature introduces. It reuses an identifier after any delete, since the count drops. Seeding does not make that worse, and fixing it is out of scope here — but the seeder must not pretend the scheme is safe, so `PersonaCatalogueValidationTest` asserts no duplicate account identifiers exist after seeding. If the underlying scheme is repaired later, the seeder inherits the fix for free by virtue of sharing the helper.

`Customer.customerId` is `GenerationType.IDENTITY` and `User.userId` is a UUID assigned in `@PrePersist`, so neither needs special handling — and FR-002's decision to look personas up by login identity rather than by identifier means none of these values are ever pinned.

**Alternatives considered**:
- *Reserved high identifier range for seeds (e.g. 900000+)*: rejected. It reads as safer but collides the moment `count()` reaches it, and it pins identifiers, which FR-002 forbids.
- *Adding `@GeneratedValue` to `Account`*: rejected as out of scope. It is a schema change to a core banking entity, touching every account-creating path — a ticket of its own, not a side effect of adding test data.

---

## R6 — What format serves as both the human catalogue and the test source of truth?

**Decision**: A single YAML file, `backend/src/main/resources/personas/voltio-personas.yaml`, bound to Java records via `@ConfigurationProperties`, and read by the tests through one accessor class (`Personas`).

**Rationale**: Clarification ruled out both a doc-generation pipeline and a second derived copy — the same artifact must serve a person reading it and a test asserting against it. YAML satisfies both: it is directly readable with comments, and it already has a precedent in this project. [risk-score-rules.yaml](backend/src/main/resources/risk-score-rules.yaml) is hand-edited, human-readable, comment-carrying, loaded through `spring.config.import`, and covered by its own test ([RiskScoreRulesTest.java](backend/src/test/java/com/group1/banking/config/RiskScoreRulesTest.java)) — precisely the pattern this feature needs, already working in this codebase.

Tests read expected values through `Personas` rather than binding the YAML independently, so there is exactly one parse path and one place a schema change has to be absorbed.

**Alternatives considered**:
- *JSON*: rejected. No comments, so the "why this persona exists" explanation would have to live somewhere else — creating the second copy that was explicitly ruled out.
- *Markdown table parsed by tests*: rejected. Most readable, most fragile to parse; a formatting tidy-up would break the tests.
- *Java constants as the source of truth with generated documentation*: rejected — that is the generation pipeline the clarification excluded.

---

## Cross-cutting: what this research deliberately did **not** resolve

- **Exact monetary amounts** for each persona. The bands and margins are fixed above; the specific figures are a task-level choice constrained by FR-023 and verified by the validation test.
- **~~Whether QA runs H2, MySQL or PostgreSQL.~~ Settled 2026-09-10: PostgreSQL.** The design remains storage-agnostic — it writes through JPA repositories and uses no vendor-specific SQL — but verification is no longer storage-agnostic: FR-009a requires the seeding tests to run against the same engine and Flyway-managed schema the application uses, so a live PostgreSQL is now a prerequisite for running them.
- **Repairing `nextAccountId()`.** Named in R5 as pre-existing fragility, explicitly out of scope, and left as a `ponytail:`-style note for whoever owns account creation.
