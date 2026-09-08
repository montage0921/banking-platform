# Tasks: Shared Seed Personas & Baseline Dataset

**Input**: Design documents from `/specs/004-shared-seed-personas/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/persona-catalogue.md](./contracts/persona-catalogue.md)

**Tests**: Required. This feature changes no endpoint, but its entire value is a guarantee about data — FR-019 and FR-020 make catalogue validation the only mechanism that detects drift, so the tests *are* the deliverable, not a companion to it. The constitution's Testing Rules also bind the two guard paths (SCR-001).

**Organization**: Grouped by user story so each is independently implementable and testable.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete work)
- **[Story]**: US1–US4, mapping to the user stories in [spec.md](./spec.md)

## Path Conventions

Web app layout per [plan.md](./plan.md): backend at `backend/src/`, frontend at `src/`. **The frontend is untouched by this feature.**

---

## Two findings that change the shape of this work

Both were verified against the codebase before writing these tasks, and both narrow scope rather than expand it.

**1. No automated test depends on shared seeded data.** Confirmed by census: 12 `@WebMvcTest`, 13 `@DataJpaTest`, and 2 `@SpringBootTest` across 71 test classes. The only full-context test, [AuthCustomerIntegrationTest.java](backend/src/test/java/com/group1/banking/integration/AuthCustomerIntegrationTest.java), runs against its own `jdbc:h2:mem:testdb` with `create-drop` and registers its users through the API. So there is **no test-migration work** — the migration is documentation, exactly as stated.

This has a consequence for FR-020 worth stating plainly: "catalogue validation runs wherever the four features' tests run" cannot be satisfied literally, because slice tests never load the seeder. It is satisfied *in effect* by making catalogue validation a standalone `@SpringBootTest` in the same `mvn test` invocation — a drift still fails the build at the moment it is introduced, which is the requirement's intent. T024 covers this.

**2. Only one of the four features actually has hardcoded fixtures.** The instruction to migrate "the four features' quickstart docs" rests on a premise that does not hold:

| Feature | Spec directory | Quickstart | Hardcoded fixtures? |
|---|---|---|---|
| Goal Tracker | `specs/001-savings-goals/` | Yes | **Yes** — an `INSERT`-style "Assume test customer exists" block with `account_id = 100/101/102` |
| Chatbot (confirmation gate) | `specs/002-agent-confirmation-gate/` | No | Informal only — plan.md:1672 says "log in as a seeded customer with at least two open accounts", naming no persona |
| Chatbot (GIC rates) | `specs/003-agent-multistep-gic-rates/` | Yes | No — refers generically to "that customer" |
| Admin Control | — | — | No spec directory exists |
| Risk Scoring | — | — | No spec directory exists; code only, on `feature/risk-scoring` |

So the migration is T027–T030: one genuine fixture deletion, two vague references made concrete, and no work for the two features that have no documents. Nothing is deferred to a follow-up ticket — there is simply less to migrate than four documents' worth.

**Explicitly not migrated**: `specs/001-savings-goals/contracts/ErrorCodes.md`. Its `account_id: 42` / `customer_id: 100` values are illustrative fields inside example error payloads documenting response *shape*. They are not fixtures and rewriting them as persona logins would make the API documentation worse. T031 records this decision so nobody re-opens it.

---

## Phase 1: Setup

**Purpose**: Package skeleton and configuration keys. No behaviour yet.

- [X] T001 Create the seed package directory `backend/src/main/java/com/group1/banking/seed/` and the catalogue resource directory `backend/src/main/resources/personas/`
- [X] T002 [P] Add seeding configuration keys to `backend/src/main/resources/application.properties`: `app.seed.personas.enabled=false` (explicit, so the safe default is visible rather than implied) and a commented `app.seed.personas.environment` example, following the commenting style of the existing chatbot and MCP blocks in that file
- [X] T003 [P] Create the test package directory `backend/src/test/java/com/group1/banking/seed/`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The catalogue, the guards, and the date anchor. Every user story depends on all three. **No user story can start until this phase completes.**

### Catalogue binding

- [X] T004 Define the immutable catalogue records in `backend/src/main/java/com/group1/banking/seed/PersonaCatalogue.java` — `PersonaCatalogue`, `Persona`, `SeedAccount`, `SeedGoal`, `SeedTransaction`, `Expectations` — with fields exactly as specified in [data-model.md](./data-model.md) Part 1 and [contracts/persona-catalogue.md](./contracts/persona-catalogue.md). Use Java records; reuse existing enums (`RoleName`, `AccountType`, `AccountStatus`, `TransactionDirection`, `TransactionStatus`, `SavingsGoalStatus`, `RiskScoreStatus`) rather than redeclaring string constants
- [X] T005 Implement `@ConfigurationProperties` loading in `backend/src/main/java/com/group1/banking/seed/PersonaCatalogueProperties.java`, reading `backend/src/main/resources/personas/voltio-personas.yaml`. Follow the binding approach already used for `risk-score-rules.yaml` (see [RiskScoreRules](backend/src/main/java/com/group1/banking/config/) and its test) so there is one YAML-config idiom in this codebase, not two
- [X] T006 Implement load-time invariant checks C1–C10 from [contracts/persona-catalogue.md](./contracts/persona-catalogue.md) in `backend/src/main/java/com/group1/banking/seed/PersonaCatalogueProperties.java`. All ten must fail fast at startup, before any row is written, so a malformed catalogue can never produce a partial environment (FR-024). C6 and C7 are structural guards — they make deleting the sparse or operations persona a build failure
- [X] T007 [P] Write `backend/src/test/java/com/group1/banking/seed/PersonaCatalogueLoadTest.java` covering each of C1–C10 with a deliberately malformed catalogue per invariant, asserting the specific failure. One test method per invariant so a failure names which rule broke

### Guards

- [X] T008 Implement the two independent guards in `backend/src/main/java/com/group1/banking/seed/SeedGuard.java` per [research.md](./research.md) R1: guard (a) the `app.seed.personas.enabled` flag, guard (b) refusal when an active Spring profile is `prod`/`production` or `app.seed.personas.environment=production`. Either guard alone must be sufficient to prevent seeding
- [X] T009 Make a refusal distinguishable from a failure (SCR-002) in `backend/src/main/java/com/group1/banking/seed/SeedGuard.java` — refusal messages must name which guard triggered and state that seeding was deliberately prevented, so an operator staring at a persona-less environment can tell "blocked" from "broken"
- [X] T010 [P] Write `backend/src/test/java/com/group1/banking/seed/SeedGuardTest.java` covering all five configurations in [quickstart.md](./quickstart.md) Scenario 6: flag absent, flag false, flag true + no production signal, flag true + `prod` profile, flag true + `environment=production`. The last two are the ones that matter — assert refusal, not merely absence of seeding

### Date anchoring

- [X] T011 Implement `backend/src/main/java/com/group1/banking/seed/PersonaDateAnchor.java` per [research.md](./research.md) R4: capture a single `Instant` **once per seed or reset run** and resolve `daysAgo`, `monthsAgo`, `targetDaysAhead` and `repeatMonthly` expansion against it. Capturing once, not per row, is the requirement — a run spanning midnight must not produce a persona straddling two "todays"
- [X] T012 [P] Write `backend/src/test/java/com/group1/banking/seed/PersonaDateAnchorTest.java` asserting: all offsets in one run resolve against one anchor; `repeatMonthly` expands to the correct count at correct intervals; a run at a simulated midnight boundary produces internally consistent dates

### Shared account identifier helper

- [X] T013 Extract the account identifier scheme into `backend/src/main/java/com/group1/banking/seed/` or a shared helper reachable by both callers, so `PersonaSeeder` and [AccountService.java:490-496](backend/src/main/java/com/group1/banking/service/impl/AccountService.java#L490-L496) compute `accountId = count() + 1000` and `accountNumber = String.format("ACC%010d", id)` from **one** implementation. Per [research.md](./research.md) R5, a seeder with its own scheme collides the moment `AccountService` next creates an account. Add a `ponytail:`-style comment recording that `count() + 1000` reuses identifiers after a delete — pre-existing fragility, explicitly out of scope here, inherited for free if repaired later

**Checkpoint**: catalogue loads and validates, guards refuse correctly, dates anchor once per run, identifiers come from one place. User stories can now proceed.

---

## Phase 3: User Story 1 — The primary personas exist and are usable (P1) 🎯 MVP

**Goal**: A clean non-production environment comes up with the salaried customer, goal saver, and operations user present, each carrying the data its scenario needs, with zero manual data entry.

**Independent test**: Per [quickstart.md](./quickstart.md) Scenario 1 — wipe `backend/data/digitalbankdb*`, start with `--app.seed.personas.enabled=true`, confirm the reserved logins exist with their accounts and transactions.

- [X] T014 [US1] Author the salaried, goalSaver and operations personas in `backend/src/main/resources/personas/voltio-personas.yaml`, following the worked example in [contracts/persona-catalogue.md](./contracts/persona-catalogue.md). Apply the band margins from [research.md](./research.md) R3: 18 months of history for the salaried persona (6× the 3-month risk minimum), goal progress at 45% (mid-band between the 30 and 60 cut points), spending-to-income ratio near 0.6 (between the 0.5 and 0.7 bands). Write a real `purpose` for each — it is the field a tester reads to choose a persona
- [X] T015 [US1] Implement persona creation in `backend/src/main/java/com/group1/banking/seed/PersonaSeeder.java`: `User` (username = reserved login identity, a real hash of a documented non-production password), `Customer`, `Account` via the T013 helper, `Transaction` with `timestamp` set explicitly from the anchor. **Setting `timestamp` explicitly is load-bearing** — [Transaction.java](backend/src/main/java/com/group1/banking/entity/Transaction.java)'s `@PrePersist` defaults it to `Instant.now()`, which would silently discard the anchor and flatten every persona's history to today
- [X] T016 [US1] Implement `SavingsGoal` creation in `backend/src/main/java/com/group1/banking/seed/PersonaSeeder.java`, honouring the `uq_sg_customer_account` constraint (one goal per account per customer, per [data-model.md](./data-model.md)). Goal progress is derived from account balance ÷ target amount because `currentBalance` and `progressPercentage` are `@Transient` — set the balance to produce the intended percentage rather than trying to persist the percentage
- [X] T017 [US1] Make each persona's creation atomic in `backend/src/main/java/com/group1/banking/seed/PersonaSeeder.java` (FR-024): a failure mid-persona rolls back to ABSENT rather than leaving a half-built customer that reads as legitimate
- [X] T018 [US1] Wire the guarded entry point in `backend/src/main/java/com/group1/banking/seed/PersonaSeedRunner.java` as an `ApplicationRunner` annotated `@ConditionalOnProperty(name = "app.seed.personas.enabled", havingValue = "true")`, delegating the production check to `SeedGuard`. Do **not** expose seeding over HTTP — no controller, no DTO, per the [plan.md](./plan.md) constitution gate
- [X] T019 [P] [US1] Write `backend/src/test/java/com/group1/banking/seed/Personas.java`, the test-side accessor. This is the **only** path by which tests may name a persona or read an expected value (FR-017): `byKey(String)`, `customerIdOf(Persona)`, `loginOf(Persona)`. One parse path means a schema change is absorbed in one place
- [X] T020 [US1] Write `backend/src/test/java/com/group1/banking/seed/PersonaSeedingTest.java` asserting all three personas exist after seeding a clean in-memory database, each with the accounts, transactions and goals its catalogue entry describes, and that no manual data entry is required

**Checkpoint**: the three personas exist — but the single-source-of-truth guarantee does not yet. US1 is not done until T029, T031 and T032 land (see the MVP definition below). Without them, nothing fails when someone hardcodes a value elsewhere, and the catalogue is a document rather than a source of truth.

---

## Phase 4: User Story 2 — A deliberately sparse persona exercises insufficient-data paths (P2)

**Goal**: One persona reproducibly triggers both the Chatbot fallback and the Risk Scoring insufficient-data status.

**Independent test**: Per [quickstart.md](./quickstart.md) Scenario 2 — `./mvnw test -Dtest=SparsePersonaThresholdTest`.

- [X] T021 [US2] Author the sparse persona in `backend/src/main/resources/personas/voltio-personas.yaml` per [research.md](./research.md) R2: exactly two SUCCESS DEBIT transactions, both at `daysAgo: 10`, no savings goal. Record in the `purpose` field *why it is two and not zero* — zero passes both checks vacuously and would not catch a regression where the chatbot's `>=` comparison flipped to `>`
- [X] T022 [US2] Write `backend/src/test/java/com/group1/banking/seed/SparsePersonaThresholdTest.java` asserting **both** consumers on the same persona: `RiskScoreService` returns `INSUFFICIENT_DATA` with code `RISK_SCORE_INSUFFICIENT_DATA` and null score/level/explain ([RiskScoreService.java:224-240](backend/src/main/java/com/group1/banking/service/impl/RiskScoreService.java#L224-L240)); `SavingsChatContextService.getSpendByCategory` returns `sufficientData = false` ([SavingsChatContextService.java:69-97](backend/src/main/java/com/group1/banking/service/impl/SavingsChatContextService.java#L69-L97))
- [X] T023 [US2] Add margin assertions to `backend/src/test/java/com/group1/banking/seed/SparsePersonaThresholdTest.java` (FR-023): the earliest transaction is far newer than the 3-month `minMonths` window, and the qualifying transaction count is strictly below `min-for-personalization` rather than equal to it. Failure messages must name *which* threshold was crossed — the two are configured independently and drift independently

**Checkpoint**: both fallback paths are pinned by one persona.

---

## Phase 5: User Story 3 — The same scenario produces the same result every run (P2)

**Goal**: Repeated runs give identical outcomes; re-seeding never duplicates; a scoped reset restores exactly its named personas.

**Independent test**: Per [quickstart.md](./quickstart.md) Scenarios 3 and 4.

- [X] T024 [US3] Implement fill-in-missing application in `backend/src/main/java/com/group1/banking/seed/PersonaSeeder.java` (FR-012): detect an existing persona by its reserved login (the `users.username` unique constraint is what makes this detectable), skip it entirely, and never overwrite. Re-applying must produce no duplicate user, account, transaction or goal
- [X] T025 [US3] Implement per-persona reset in `backend/src/main/java/com/group1/banking/seed/PersonaResetService.java` (FR-013, FR-014): `reset(String... personaKeys)`, delete-then-recreate rather than diff-and-patch, re-anchoring dates to the reset moment. Delete in dependency order — `Customer.accounts` cascades `ALL` and `riskScoreHistory` cascades with `orphanRemoval`, but **transactions and savings goals do not cascade** and must be deleted explicitly (see [data-model.md](./data-model.md) Part 2)
- [X] T026 [US3] Ensure reset is never automatic in `backend/src/main/java/com/group1/banking/seed/PersonaResetService.java` (FR-013) — it must not run on startup, and resetting the whole baseline must be expressible as resetting every persona with no separate code path of its own
- [X] T027 [P] [US3] Write `backend/src/test/java/com/group1/banking/seed/PersonaResetScopeTest.java` covering the three behaviours in [quickstart.md](./quickstart.md) Scenario 4, of which the middle is the point: mutate A → re-apply baseline → A stays mutated; mutate A and B → reset A only → **B stays mutated** (FR-015, SC-010); apply baseline twice → nothing duplicated
- [X] T028 [P] [US3] Write `backend/src/test/java/com/group1/banking/seed/PersonaDeterminismTest.java` (FR-009): seeding a clean database twice produces identical balances, transaction counts, goal progress and risk outcomes. Assert on outcomes and relative positions only — **never on literal timestamps**, which differ between runs by design (FR-011)

**Checkpoint**: the baseline is trustworthy and safe to share.

---

## Phase 6: User Story 4 — One dataset serves all four features (P3)

**Goal**: The catalogue is the single source of truth; drift fails the build; the features' documentation points at the personas instead of hand-rolled fixtures.

**Independent test**: Per [quickstart.md](./quickstart.md) Scenario 5 — run the validation test, then deliberately edit a persona's balance and confirm the test fails.

### Catalogue as source of truth

- [X] T029 [US4] Write `backend/src/test/java/com/group1/banking/seed/PersonaCatalogueValidationTest.java` as a `@SpringBootTest` (FR-019): for every persona, assert seeded rows match its `expectations` block — risk status and level, chatbot sufficiency, goal progress recomputed from balance ÷ target, restriction-management capability. Also assert no duplicate account identifiers exist after seeding ([research.md](./research.md) R5). At MVP time this covers the three US1 personas; extend it to the sparse persona when T021 lands.
- [X] T030 [US4] Add the band-boundary margin check to `backend/src/test/java/com/group1/banking/seed/PersonaCatalogueValidationTest.java` (FR-023): each persona's computed risk score must sit clear of the 25/45/70 level cut points by a stated margin. This is a test rather than a hand calculation because the weights live in [risk-score-rules.yaml](backend/src/main/resources/risk-score-rules.yaml) and can be retuned
- [X] T031 [US4] Confirm `PersonaCatalogueValidationTest` runs in the default `./mvnw test` invocation (FR-020) so a persona change that invalidates any feature's expectation fails at the moment it is made. Document in the class javadoc *why* this is a standalone test rather than embedded in each feature's tests: the four features use `@WebMvcTest`/`@DataJpaTest` slices that never load the seeder, so same-invocation is the achievable form of "wherever the four features' tests run"
- [X] T032 [US4] Verify drift detection actually works by temporarily changing a persona's balance in `backend/src/main/resources/personas/voltio-personas.yaml`, confirming `PersonaCatalogueValidationTest` **fails**, then reverting. A validation test that cannot fail is not validation — if it passes, FR-017 is violated

### Documentation migration (no test migration required — see findings above)

- [~] T033 [P] [US4] Delete the hardcoded fixture block in `specs/001-savings-goals/quickstart.md` — the `-- Assume test customer exists` SQL with `customer_id = 1` and `account_id = 100/101/102` — and replace it with a reference to the goalSaver persona by its reserved login, linking to [contracts/persona-catalogue.md](./contracts/persona-catalogue.md). This is the one genuine fixture in the repository and the primary target of this migration **PARTIAL — do not mark complete.** Only the Test Data preamble was migrated. All ten scenarios still reference ACC-00100/00101/00102 and absolute dates (lines 83, 112, 134, 172, 195, 220, 261, 334, 371, 410–426 — about 25 references). They are blocked on persona coverage, not on effort: Scenario 2 needs a goal that is already OVERDUE (goalSaver’s target date is 90 days ahead) and Scenarios 4/9 need a third account. Unblock by adding those accounts to the catalogue, then finish this task.
- [X] T034 [P] [US4] Replace the vague "log in as a seeded customer with at least two open accounts" in `specs/002-agent-confirmation-gate/plan.md` (manual smoke test, around line 1672) with the named persona that actually has two open accounts, so the smoke test stops depending on whatever data happens to be present
- [X] T035 [P] [US4] Update `specs/003-agent-multistep-gic-rates/quickstart.md` to name the specific persona for its `chat_interaction_log` verification step instead of the generic "that customer"
- [X] T036 [US4] Record in `specs/001-savings-goals/contracts/ErrorCodes.md` — as a short note, not a rewrite — that its `account_id: 42` / `customer_id: 100` values are illustrative fields in example error payloads documenting response shape, **not** fixtures, and are deliberately left alone. This closes the question rather than leaving the next reader to re-open it
- [X] T037 [US4] Add a short "Using the shared personas" section to `SETUP.md` covering how to enable seeding, the four reserved logins, and the reset action — the entry point someone new to the repository will actually find

**Checkpoint**: one dataset, one source of truth, and the documentation no longer instructs anyone to hand-build customers.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T038 [P] Verify the safe default by hand per [quickstart.md](./quickstart.md) Scenario 6: wipe the database, run `./mvnw spring-boot:run` with **no** flag, confirm `SELECT COUNT(*) FROM users WHERE username LIKE 'seed.%'` returns 0. Everything else rests on this guard
- [X] T039 [P] Run the full suite `cd backend && ./mvnw test` and confirm all 71 pre-existing test classes still pass. Particular attention to [AuthCustomerIntegrationTest.java](backend/src/test/java/com/group1/banking/integration/AuthCustomerIntegrationTest.java), the only full-context test — it loads the whole application, so a seed runner that ignored its guard would corrupt it
- [X] T040 Confirm the constitution gates in [plan.md](./plan.md) still hold after implementation: no new endpoint, no DTO change, no `SecurityConfig` matcher, no new error code, no frontend file touched, and Principle IX theme parity untriggered because no UI was added
- [X] T041 [P] Review the seeded password handling in `backend/src/main/resources/personas/voltio-personas.yaml` and `PersonaSeeder` against SCR-003: credentials documented, non-production only, granting nothing beyond the persona's legitimate role
- [X] T042 Walk all six [quickstart.md](./quickstart.md) scenarios end to end and correct any step that does not match the built behaviour. The quickstart is the artifact QA will actually follow

---

## Dependencies

```text
Phase 1 (Setup: T001–T003)
        ↓
Phase 2 (Foundational: T004–T013)  ← BLOCKS EVERYTHING
        ↓
   ┌────┴──────────────┬───────────────────┐
   ↓                   ↓                   ↓
US1 (T014–T020)   US2 (T021–T023)    US3 (T024–T028)
   P1 · MVP         needs T014-T018    needs T014-T018
   │                   │                   │
   └───────────────────┴───────────────────┘
                       ↓
              US4 (T029–T037)
        needs all personas to exist first
                       ↓
              Phase 7 (T038–T042)
```

**Story dependencies**: US2, US3 and US4 all need the seeder from US1 (T014–T018) — this feature's stories are genuinely sequential in their foundation, unlike a typical multi-endpoint feature. Once US1 lands, US2 and US3 are independent of each other and can run in parallel. US4's validation work needs every persona to exist; its documentation tasks (T033–T037) do not, and can start as soon as the reserved logins are fixed in T014 and T021.

---

## Parallel Execution Examples

**Phase 2** — three independent test files:

```text
T007 (PersonaCatalogueLoadTest) ‖ T010 (SeedGuardTest) ‖ T012 (PersonaDateAnchorTest)
```

**Phase 5** — reset and determinism tests touch different files:

```text
T027 (PersonaResetScopeTest) ‖ T028 (PersonaDeterminismTest)
```

**Phase 6** — all four documentation migrations are separate files in separate spec directories:

```text
T033 (001 quickstart) ‖ T034 (002 plan) ‖ T035 (003 quickstart) ‖ T036 (001 ErrorCodes)
```

**Not parallelizable**: T015, T016, T017, T024 and T025 all edit `PersonaSeeder.java` / `PersonaResetService.java` and must be sequential.

---

## Implementation Strategy

**MVP = Phase 1 + Phase 2 + US1 + the enforcement tasks (T001–T020, plus T029, T031, T032).** That delivers the named cast in a guarded, reproducible form *and* the test that makes the catalogue authoritative. T029/T031/T032 are pulled forward out of US4 deliberately: they are what turns “we have personas” into “the personas are the truth”, and shipping the personas without them leaves a shared dataset that nothing defends. At this point T029 covers the three US1 personas; it is extended to the sparse persona when T021 lands.

**Increment 2 = US2 (T021–T023).** Small and high-value: it pins two fallback paths that no current test covers and that will otherwise rot silently, because normal seeded data never reaches them.

**Increment 3 = US3 (T024–T028).** Makes the baseline safe to share. Until per-persona reset exists, a shared QA environment is a collision waiting to happen.

**Increment 4 = the rest of US4 (T030, T033–T037).** T029, T031 and T032 were promoted into the MVP above. What remains is the band-boundary margin check and the documentation migration, which is mostly deletion.

**On effort distribution**: the seeding machinery (Phases 1–5, T001–T028) is the bulk of the work. The migration the user asked to include is genuinely small — 5 documentation tasks, no test changes — because the codebase's tests were already self-contained. That is a good outcome, not a shortcut: it means adopting the shared baseline costs almost nothing in rework.

---

## Implementation notes (recorded during `/speckit-implement`)

All 42 tasks complete. Five deviations from the task text, each made deliberately:

1. **T004/T005 - Lombok `@Data`, not records; one class, not two.** The catalogue binds with
   `@Configuration @ConfigurationProperties` and nested `@Data` classes, matching
   `RiskScoreRules` exactly. Records would have introduced a second YAML-config idiom, which
   T005 itself argued against. Binding and the C1-C10 invariants live together in
   `PersonaCatalogue.java`; a separate `PersonaCatalogueProperties` would have been two files
   for one job. The YAML root key is `persona-catalogue:` rather than a bare `personas:` list,
   because `spring.config.import` binding needs a distinctive prefix - the contract was updated
   to match.

2. **T013 - helper landed in `util/`, not `seed/`.** `AccountIdentifiers` is shared by
   `AccountService` and the seeder, so it belongs in the existing `util` package rather than in
   a seeding package the service layer would have to reach into.

3. **T038 - automated as well as manual.** The default-off check became
   `SeedDefaultOffTest`, which asserts both that no persona is seeded *and* that the
   `PersonaSeedRunner` bean is never created. A guarantee everything else rests on should not
   depend on someone remembering to run it by hand.

4. **`riskStatus` values are `OK` / `INSUFFICIENT_DATA`.** The design documents assumed
   `CALCULATED`; the real `RiskScoreStatus` enum has no such constant. Catalogue, contract and
   data model were corrected. `riskLevel` is also typed as the real `RiskScoreLevel` enum
   rather than a `String`, so a typo fails at load rather than at assertion time.

5. **Three repository methods added.** `AccountRepository.findAllByCustomerCustomerId`,
   `findMaxAccountId`, `SavingsGoalRepository.findAllByAccountAccountId` and
   `TransactionRepository.findAllByAccountAccountId`. Reset needs them because savings goals and
   transactions are owned by `Account` without a cascading collection. Purely additive.

### One thing found while building

`AccountService.nextAccountId()` derives ids from `accountRepository.count()`, which is only
correct when rows are inserted one at a time with a flush between. Seeding inserts several
accounts inside one transaction, where the count lags the pending inserts and issues the same
id twice - this failed with a primary-key violation on the first real run. This is a sharper
form of the fragility recorded in research R5 (which noted only the delete case).

The seeder works around it by taking `max(accountId) + 1` as a floor and stepping locally,
leaving `AccountService`'s scheme untouched. Both callers now share `AccountIdentifiers`, so a
proper identity strategy on the `account` table - the real fix, out of scope here - would be
inherited by both.

### Test results

- **730 tests, 3 failures.** All three pre-existing and unrelated, confirmed by stashing this
  work and running them on a clean tree: a DTO field-name mismatch in
  `RiskScoreServiceInsufficientDataTest`, and a hard-vs-soft delete plus a missing decimal
  validation in `SavingsGoalServiceTest`.
- **8 new seed test classes, 46 tests, all passing.**
- Drift detection verified for real (T032): the goal saver's balance was changed to 3000.00,
  `PersonaCatalogueValidationTest` failed naming the persona and both values, and the change was
  reverted.
