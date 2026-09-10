# Feature Specification: Shared Seed Personas & Baseline Dataset

**Feature Branch**: `004-shared-seed-personas`

**Created**: 2026-09-07

**Status**: Draft

**Input**: User description: "As anyone building or testing a feature, I want a consistent set of seeded customer personas and sample data available across dev, QA, and demos, so that scenarios are reproducible and every feature is validated against the same known baseline."

## Clarifications

### Session 2026-09-07

- Q: How should the platform decide whether seeding is allowed to run in a given environment? → A: Opt-in flag **and** a hard refusal if the environment identifies as production, whichever triggers first (defense in depth).
- Q: What should identify a seeded persona so that tests, the catalogue, and the seeding process all refer to the same customer? → A: A natural business key — each persona owns a reserved, recognizable login/email unique by convention; identifiers are generated normally and personas are looked up by that key.
- Q: When the reset action is invoked in an environment more than one person is using, what should happen to work already in progress there? → A: Reset is scoped per persona — a tester resets only the personas their scenario touches, leaving other personas, and other people's in-flight work, untouched.
- Q: Where should the expected outcome for each persona live — the value a test asserts against? → A: A single catalogue artifact that is the source of truth. Tests read expected outcomes from it, and the same artifact is directly readable by a person. It is validated, not generated — there is no doc-generation pipeline and no second copy.
- Q: When someone changes the baseline dataset, how should the four features find out their expectations may no longer hold? → A: No separate mechanism. The catalogue validation already fails at the moment of the change, and that failure is the notification — no baseline version number, change log, or ownership sign-off.

### Session 2026-09-10

*Context: the role-definition dependency landed via the `justin/feature/springai` merge, replacing the two-role model (CUSTOMER/ADMIN) with four roles (BANK_ADMINISTRATOR, RISK_ANALYST, COMPLIANCE_AUDIT_OBSERVER, RETAIL_CUSTOMER). This session resolves what that changes. The feature was already implemented when these were asked, so each answer implies rework.*

- Q: Should the seeded baseline gain personas for the two roles that now exist but have nobody representing them — Risk Analyst and Compliance/Audit Observer? → A: Yes — add one persona per unrepresented role, so all four roles have a seeded identity.
- Q: The seeding tests run on in-memory H2 but the application now runs on PostgreSQL — should the reproducibility guarantee hold across both, or only on the storage the application actually uses? → A: PostgreSQL only. The seeding tests run against a real PostgreSQL instance with the Flyway-managed schema, matching the deployed configuration exactly; the in-memory H2 substitute is dropped.
- Q: Now that all four target roles exist, should the `provisionalRole` field be removed from the persona catalogue? → A: Yes — remove the field from the schema entirely, along with FR-021's provisional-role mechanism. The gap it existed to bridge has closed.
- Q: Should the ability to place and lift account restrictions still belong to exactly one seeded persona, now that three non-customer roles exist? → A: Derive it — validation checks the capability against the platform's actual authorization rules rather than equating it with a hardcoded role name, so the catalogue tracks permissions as they evolve.
- Q: With the application now on a PostgreSQL server rather than a per-developer H2 file, does the baseline need to tolerate several people seeding and resetting the same database at once? → A: No change. Per-persona reset scoping plus the catalogue's scenario mapping is sufficient; overlapping resets remain a human coordination matter, not something the dataset arbitrates.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - The primary personas exist and are usable (Priority: P1)

A developer, tester, or presenter opens a freshly prepared non-production environment and finds a small, named cast of customers already present: a salaried customer with regular pay and spending, a customer part-way toward a near-term savings goal, and an operations user who can manage account restrictions. Nobody has to invent a customer, hand-enter transactions, or ask a teammate "which account do I use for this?" before starting work.

**Why this priority**: Without the cast existing, nothing else in this feature has anything to stand on. On its own this already removes the single biggest source of setup friction and of "works on my machine" demo failures, so it is a viable standalone slice.

**Independent Test**: Prepare a clean non-production environment, apply the seed, and confirm all three personas are present, distinctly identifiable, and each carries the account, transaction, and goal data its intended scenario needs — without any manual data entry.

**Acceptance Scenarios**:

1. **Given** a clean non-production environment, **When** the baseline seed is applied, **Then** a salaried-customer persona exists with recurring income and regular spending activity covering at least the minimum history window that risk scoring requires
2. **Given** a clean non-production environment, **When** the baseline seed is applied, **Then** a goal-saving persona exists with at least one active goal that has a near-term target date and partial progress toward it
3. **Given** a clean non-production environment, **When** the baseline seed is applied, **Then** an operations-user persona exists with privileges sufficient to place and lift account restrictions
4. **Given** the seeded personas, **When** someone needs to pick one for a scenario, **Then** each persona is identifiable by its reserved login identity, which is recognizable on sight and does not change between seed runs or between environments

---

### User Story 2 - A deliberately sparse persona exercises insufficient-data paths (Priority: P2)

A tester needs to confirm that the Chatbot degrades to its fallback response and that Risk Scoring reports an insufficient-data status rather than inventing a score. Instead of hand-crafting an empty customer each time, they pick the persona that exists specifically to be data-poor.

**Why this priority**: Insufficient-data handling is an explicit requirement of two shipped features and is the path most likely to rot silently, because normal seeded data never reaches it. It depends on the persona set existing (P1) but delivers value on its own the moment it does.

**Independent Test**: Run the Chatbot against the sparse persona and confirm the fallback response; request a risk score for the same persona and confirm the insufficient-data status with no score, band, or explanation reported.

**Acceptance Scenarios**:

1. **Given** the sparse persona, **When** a risk score is requested for them, **Then** the result is the insufficient-data status with no score value reported
2. **Given** the sparse persona, **When** the Chatbot is asked a question that would require spending or goal history, **Then** it returns its defined fallback response rather than a fabricated answer
3. **Given** the sparse persona, **When** their seeded transaction history is inspected, **Then** it falls below the configured minimum-history threshold by a clear margin rather than sitting on the boundary

---

### User Story 3 - The same scenario produces the same result every run (Priority: P2)

A tester runs a scenario on Monday and again on Friday, and a presenter runs the same scenario live in a demo. All three get the same numbers. Nobody has to explain why the risk band moved or why the goal progress bar sits somewhere different than it did in the rehearsal.

**Why this priority**: Reproducibility is the stated reason this dataset exists. Personas that drift are worse than no shared personas, because they produce confident, wrong baselines.

**Independent Test**: Run the same scenario against the same persona three times in a row, and again after a reset, and confirm the observable outcomes are identical each time.

**Acceptance Scenarios**:

1. **Given** a seeded persona and a scenario, **When** that scenario is run repeatedly without any intervening change, **Then** the observable outcome is identical each time
2. **Given** an environment where a previous test run mutated seeded data, **When** the reset action is invoked, **Then** the personas return to their documented starting state
3. **Given** an already-seeded environment, **When** the baseline is applied again without a reset, **Then** no persona, account, transaction, or goal is duplicated and no existing data is overwritten
4. **Given** the baseline seed, **When** it is applied to two separate clean environments on different days, **Then** both produce personas with identical balances, transaction sets, goal progress, and risk outcomes
5. **Given** a persona seeded several months ago in a long-lived environment, **When** its risk score is requested today, **Then** it still falls on the same side of the minimum-history threshold as its catalogue entry documents
6. **Given** a shared environment where two people are working on different personas, **When** one of them resets only the persona their scenario touches, **Then** the other person's persona and in-flight work are unaffected

---

### User Story 4 - One dataset serves all four features (Priority: P3)

Someone working on the Goal Tracker uses the same seeded customers as someone working on Risk Scoring. When the Chatbot reports a customer's spending, it matches what the Goal Tracker shows for that same customer. No feature carries its own private copy of "the test customers".

**Why this priority**: The consistency payoff is what turns a set of personas into a baseline. It is last because the earlier stories are still valuable if only some features have adopted the shared set, and adoption can proceed feature by feature.

**Independent Test**: Pick one seeded persona, exercise it through all four features, and confirm each feature reflects the same underlying accounts, transactions, and goals with no contradictions.

**Acceptance Scenarios**:

1. **Given** a seeded persona, **When** the same customer is viewed through the Chatbot, Goal Tracker, Admin Control, and Risk Scoring features, **Then** all four reflect the same underlying account, transaction, and goal data
2. **Given** the shared dataset, **When** the four features' test setups are inspected, **Then** none of them defines its own duplicate persona for a scenario the shared dataset already covers
3. **Given** a change to a seeded persona's data, **When** the four features are exercised again, **Then** all four reflect the change consistently, with no feature reading a stale private copy
4. **Given** a seeded persona whose data has been changed without its catalogue entry being updated, **When** the catalogue is validated against the seeded data, **Then** the mismatch is reported as a failure rather than passing silently

---

### Edge Cases

- **A test run mutates seeded data.** A scenario that transfers money, completes a goal, or restricts an account leaves the persona in a changed state. Re-applying the baseline will not undo this — only the explicit reset will — so any scenario that requires a clean starting state must name the reset, and the personas it covers, as its first step.
- **A scenario spans two personas.** A transfer between the salaried customer and the goal saver leaves both changed, so resetting only one restores half a scenario. Scenarios that touch more than one persona must reset every persona they touch, and the catalogue must say which personas each scenario involves.
- **Two people reset overlapping personas at once.** Per-persona scoping removes most collisions but not the case where two scenarios genuinely need the same persona. The catalogue's persona-to-scenario mapping is what lets people see the overlap in advance; the dataset deliberately does not arbitrate it. No claim, lock, or per-user persona partitioning is provided — a decision taken knowingly, and worth revisiting if a shared environment ever becomes the normal way people work rather than the exception.
- **The passage of real time.** A persona whose activity was anchored to fixed calendar dates would drift past the risk-scoring minimum-history window as months pass, silently flipping a "sufficient data" persona into an insufficient-data one. Relative anchoring (FR-009) prevents this, at the cost that literal seeded timestamps differ between environments seeded on different days.
- **A long-lived environment reset months after it was first seeded.** The reset re-anchors dates to the moment of the reset, so personas return to their documented classification rather than to their original literal dates. Any external record that captured literal timestamps from the earlier seeding becomes stale.
- **Partial seeding.** If seeding is interrupted, the environment must not be left with some personas present and others missing in a way that reads as a legitimate state. The result must be either a complete baseline or an obvious failure.
- **Re-running the seed on an already-seeded environment.** Must not produce duplicate personas or duplicate transaction sets.
- **A persona sits on a threshold boundary.** If the sparse persona's history lands exactly on the minimum-history cutoff, or the goal-saver's progress lands exactly on a rounding boundary, expected outcomes become ambiguous and the persona stops being a reliable baseline.
- **Restriction powers move between roles.** The capability is declared per persona and checked against real authorization, so a permissions change that grants or revokes it shows up as a catalogue mismatch naming the persona, rather than as an unexplained failure in the Admin Control feature.
- **A new role is added to the platform.** Every recognized role needs a persona holding it (FR-001a), so adding a role without adding a persona leaves a gap. This happened once already: the role-definition story landed after the baseline was built, and two of the four new roles had no seeded identity until personas were added for them.
- **Verification storage diverges from deployed storage.** A baseline proven against a substitute database can pass every test and still fail on first contact with the real schema. Identifier generation is the known example: the account identifier scheme broke under batch insert during implementation, and that class of defect is engine-specific.
- **Seeding reaches a production-like environment.** Fictional customers appearing in a production dataset is a data-integrity incident, not a test inconvenience.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The baseline dataset MUST define a named set of personas that includes, at minimum: a salaried customer with regular activity, a customer actively saving toward a near-term goal, an operations user who manages account restrictions, a customer with deliberately sparse data, a risk analyst, and a compliance/audit observer.
- **FR-001a**: Every role the platform recognizes MUST have at least one seeded persona holding it, so no feature gated on a role is left without an identity to test with.
- **FR-002**: Each persona MUST be identifiable by a reserved, recognizable login identity that is unique by convention and does not change between seed runs or between environments. System-assigned identifiers MUST be generated normally and MUST NOT be pinned to fixed values; everything that needs to find a persona — tests, the catalogue, the seeding process, and demo scripts — MUST resolve it through this login identity.
- **FR-003**: The reserved login identities MUST follow a documented convention that makes a seeded persona recognizable on sight and distinguishable from any non-seeded account, so that seeded records can be identified in a shared environment without consulting the catalogue.
- **FR-004**: The salaried-customer persona MUST have recurring income and regular spending activity spanning at least the minimum history window that risk scoring requires to produce a score.
- **FR-005**: The goal-saving persona MUST have at least one active savings goal with a near-term target date and partial, non-boundary progress toward it.
- **FR-006**: At least one persona MUST hold privileges sufficient to place and lift account restrictions on at least one other seeded persona.
- **FR-006a**: A persona's declared restriction-management capability MUST be verified against the platform's actual authorization rules, not against a named role. Granting or removing that capability at the permissions level MUST surface as a catalogue mismatch rather than passing because a role name still matches.
- **FR-007**: The sparse persona MUST have transaction history below the configured minimum-history threshold by a clear margin, and insufficient goal and spending history to answer Chatbot questions that require it, so that both the Chatbot fallback response and the risk-scoring insufficient-data status are reachable.
- **FR-008**: All four features (Chatbot, Goal Tracker, Admin Control, Risk Scoring) MUST draw their persona-based scenarios from this single shared dataset; no feature may maintain a private duplicate of a persona the shared dataset already covers.
- **FR-009**: Applying the baseline to a clean environment MUST produce identical persona data every time — same balances, same transaction sets, same goal progress, same resulting risk outcomes.
- **FR-009a**: The reproducibility guarantee MUST be verified against the same storage engine and schema the deployed application uses, not a substitute. Verification MUST run against a real PostgreSQL instance carrying the migration-managed schema, so that behaviour which differs between engines — identifier generation in particular — is exercised where it will actually run.
- **FR-010**: Seeded activity dates MUST be generated relative to the moment of seeding rather than as fixed calendar dates, so that each persona sits in the same position relative to time-sensitive thresholds — notably the risk-scoring minimum-history window and the goal-saver's near-term target date — no matter when the seed is applied.
- **FR-011**: Because seeded dates shift with the seeding moment, scenarios and expected outcomes MUST be expressed in terms of observable results and relative positions ("has more than the minimum history", "is roughly two-thirds toward the goal") and MUST NOT assert on literal timestamps.
- **FR-012**: Applying the baseline to an already-seeded environment MUST fill in only what is missing: it MUST NOT create duplicate personas, accounts, transactions, or goals, and MUST NOT overwrite data that is already present.
- **FR-013**: A separate, explicitly invoked reset action MUST restore personas to their documented starting state, discarding changes made by previous runs. It MUST be documented as the prerequisite step for any QA run or demo that requires a guaranteed baseline, and MUST NOT run implicitly on environment startup.
- **FR-014**: Reset MUST be scopeable to a named subset of personas, so that a person working in a shared environment can restore only the personas their scenario touches. Resetting one persona MUST leave every other persona's data — and any in-flight work against it — untouched. Resetting the whole baseline MUST be expressible as resetting every persona, with no separate behaviour of its own.
- **FR-015**: Because a persona is the unit of reset, each persona's accounts, transactions, and goals MUST belong to exactly one persona, with no data shared between two personas that would make one persona's reset disturb another's state.
- **FR-016**: The dataset MUST be accompanied by a single catalogue artifact that lists each persona, its reserved login identity, the scenarios it is intended to support, which personas each scenario touches, and the expected outcome for that persona in each of the four features.
- **FR-017**: The catalogue MUST be the single source of truth for expected outcomes: tests MUST read expected values from it rather than restating them, so an expectation cannot drift from the catalogue without failing a test.
- **FR-018**: The catalogue MUST be directly readable by a person without any generation or transformation step. There MUST NOT be a second, derived copy of it — one artifact serves both the tests and the reader.
- **FR-019**: The catalogue MUST be validated against the seeded data, so that a persona whose data no longer matches its catalogue entry is reported as a failure rather than left as silent documentation drift.
- **FR-020**: Catalogue validation MUST run wherever the four features' tests run, so that a change to a persona which invalidates any feature's expectation fails at the moment of the change. This validation is the only required notification mechanism: the dataset MUST NOT carry a baseline version number, a change log, or a per-persona sign-off process.
- **FR-021**: Each persona MUST be assigned exactly one role, and that role MUST be a value the platform currently recognizes. A persona MUST NOT record an aspirational or provisional second role: the catalogue states the role a persona holds, and nothing else.
- **FR-022**: Seeded data MUST be fictional and MUST NOT contain, or be derived from, real customer information.
- **FR-023**: Persona amounts, dates, and progress values MUST be chosen to sit clear of thresholds and rounding boundaries, so each persona's expected outcome in each feature is unambiguous.
- **FR-024**: The baseline MUST apply either completely or not at all; a partially applied baseline MUST be reported as a failure rather than left in place as an apparently valid state.

### Security & Configuration Requirements *(mandatory for endpoint/proxy/auth changes)*

- **SCR-001**: Seeding MUST be gated by two independent guards, either of which is sufficient to prevent it: (a) seeding runs only where a configuration setting explicitly enables it, defaulting to disabled in every environment including a fresh developer machine; and (b) seeding refuses to run where the environment identifies itself as production, regardless of that setting.
- **SCR-002**: A refusal by either guard MUST be reported clearly enough that an operator can tell seeding was deliberately prevented, rather than silently producing an environment with no personas that reads as a seeding bug.
- **SCR-003**: Seeded credentials MUST be usable only in non-production environments and MUST NOT grant any access beyond what the persona's role legitimately carries.
- **SCR-004**: The operations-user persona's elevated privileges MUST be scoped to the seeded environment and MUST NOT require weakening any existing endpoint authorization rule to make the persona work.

### Key Entities *(include if feature involves data)*

- **Persona**: A named, reusable test identity representing one primary user type. Carries an intended purpose, exactly one role assignment, and the set of scenarios it is meant to support.
- **Persona Catalogue**: The single artifact recording who each persona is, its reserved login identity, what scenarios it supports, which personas each scenario touches, and what each of the four features is expected to report for it. It serves two audiences from one copy: a tester or presenter reads it directly to pick the right persona, and the tests read their expected values from it. Validated against the seeded data rather than generated from it.
- **Seeded Customer Profile**: The customer identity behind a persona — name, contact details, and role — all fictional.
- **Seeded Account**: An account belonging to a persona, with a defined starting balance and, where relevant, a restriction state the operations user can act on.
- **Seeded Transaction Set**: The activity history attached to a persona's accounts, shaped to place that persona deliberately above or below the thresholds that matter to Risk Scoring and the Chatbot.
- **Seeded Goal**: A savings goal attached to a persona, with a target amount, target date, and progress chosen to produce an unambiguous state in the Goal Tracker.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A person starting from a clean non-production environment has every persona available and usable in under 5 minutes, with zero manual data entry.
- **SC-002**: All four features can be demonstrated end-to-end using only seeded personas, with no ad-hoc test data created during the demo.
- **SC-003**: Running the same scenario against the same persona three times consecutively produces identical observable results on all three runs.
- **SC-004**: The sparse persona triggers the Chatbot fallback response and the risk-scoring insufficient-data status on 100% of attempts.
- **SC-005**: Zero feature-specific duplicate personas remain for scenarios the shared dataset covers, and zero expected outcomes are restated outside the catalogue.
- **SC-006**: A tester unfamiliar with the dataset can identify the correct persona for a given scenario in under 1 minute using the catalogue alone.
- **SC-007**: The same scenario run on two independently prepared environments produces the same result, with no environment-specific setup notes required to reconcile them.
- **SC-012**: Every reproducibility and catalogue-validation check runs against the same database engine and schema the deployed application uses, with zero checks relying on a substitute engine.
- **SC-008**: Six months after the dataset is defined, every persona still produces the outcome its catalogue entry documents, without anyone having adjusted the data for the passage of time.
- **SC-009**: A change to a seeded persona that invalidates any feature's expectation is reported before that change is merged, not discovered later as an unrelated feature's failing test.
- **SC-010**: A person working in a shared environment can restore the personas their scenario needs without disturbing anyone else's in-flight work, on 100% of attempts.
- **SC-011**: Seeding attempted against a production-configured environment is refused on 100% of attempts, and the refusal is distinguishable from a seeding failure.

## Assumptions

Two open questions were resolved by default rather than by decision, because the specification could not be completed without them. Both are recorded here so they can be overridden in `/speckit-clarify` without reworking the rest of the spec.

- **Date anchoring (FR-009, FR-010) — resolved as: relative to the moment of seeding.** Chosen over fixed calendar dates because risk scoring derives its sufficiency window from the current date, so fixed dates would silently reclassify the salaried persona as insufficient-data once enough months elapsed. The cost is that literal seeded timestamps differ between environments, so assertions must target outcomes rather than dates. Rejected alternative: a pinnable "as of" clock, which would give both properties but requires controllable-time machinery beyond defining personas.
- **Re-seed semantics (FR-011, FR-012) — resolved as: fill in what is missing, with a separate explicit reset.** Chosen over restore-on-every-apply because the latter would silently destroy a developer's in-progress local work each time the environment started. The cost is that the guaranteed baseline becomes a deliberate step QA and demos must remember rather than an automatic property.
- The three personas named in the source BRD (Section 1) are the salaried customer, the goal-saving customer, and the operations user. The BRD is not held in this repository, so those descriptions are taken from the feature request itself.
- The sparse persona is a fourth, distinct persona (a newly onboarded customer) rather than one of the three primary personas made data-poor, so that each primary persona can fully exercise its own scenario.
- "All four features" means the Chatbot, Goal Tracker, Admin Control, and Risk Scoring features.
- Seeding applies to non-production environments only: local development, CI, QA, and demo. Production is explicitly out of scope.
- The primary datastore is a PostgreSQL server rather than a per-developer file, so two people can in principle point at the same database. This is opt-in — the default connection target is local — and concurrent use is handled by per-persona reset scoping plus human coordination, not by locking.
- The platform recognizes four roles — Bank Administrator, Risk Analyst, Compliance/Audit Observer, and Retail Customer. Personas are seeded directly against them; the earlier two-role model and its provisional-mapping workaround no longer apply.
- The minimum-history window that separates a scorable customer from an insufficient-data one is an existing configured value; this feature consumes it rather than defining it, and positions personas clear of it in both directions.
- This feature introduces no API request/response contract changes. If a triggered seeding or reset interface is later added, the constitution's contract and error-semantics requirements apply to that work, not to this one.
- Personas may be added to the dataset later; the four defined here are the minimum baseline, not a closed set.

## Dependencies

- **Role definition story** (Bank Administrator, Risk Analyst, Compliance/Audit Observer, Retail Customer): **resolved.** Landed on 2026-09-10, replacing the two-role model. Personas are now assigned their real roles directly, and every recognized role must have a persona holding it (FR-001a, FR-021).
- **CFG-06 — shared data contracts** for the account, transaction, and user/profile services. Explicitly out of scope here: this feature defines personas and their dataset, not the contracts those services expose.
- **Existing risk-scoring minimum-history configuration**: determines where the sparse persona must sit relative to the threshold.
