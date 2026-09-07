# Feature Specification: Shared Seed Personas & Baseline Dataset

**Feature Branch**: `004-shared-seed-personas`

**Created**: 2026-09-07

**Status**: Draft

**Input**: User description: "As anyone building or testing a feature, I want a consistent set of seeded customer personas and sample data available across dev, QA, and demos, so that scenarios are reproducible and every feature is validated against the same known baseline."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - The primary personas exist and are usable (Priority: P1)

A developer, tester, or presenter opens a freshly prepared non-production environment and finds a small, named cast of customers already present: a salaried customer with regular pay and spending, a customer part-way toward a near-term savings goal, and an operations user who can manage account restrictions. Nobody has to invent a customer, hand-enter transactions, or ask a teammate "which account do I use for this?" before starting work.

**Why this priority**: Without the cast existing, nothing else in this feature has anything to stand on. On its own this already removes the single biggest source of setup friction and of "works on my machine" demo failures, so it is a viable standalone slice.

**Independent Test**: Prepare a clean non-production environment, apply the seed, and confirm all three personas are present, distinctly identifiable, and each carries the account, transaction, and goal data its intended scenario needs — without any manual data entry.

**Acceptance Scenarios**:

1. **Given** a clean non-production environment, **When** the baseline seed is applied, **Then** a salaried-customer persona exists with recurring income and regular spending activity covering at least the minimum history window that risk scoring requires
2. **Given** a clean non-production environment, **When** the baseline seed is applied, **Then** a goal-saving persona exists with at least one active goal that has a near-term target date and partial progress toward it
3. **Given** a clean non-production environment, **When** the baseline seed is applied, **Then** an operations-user persona exists with privileges sufficient to place and lift account restrictions
4. **Given** the seeded personas, **When** someone needs to pick one for a scenario, **Then** each persona is identifiable by a stable, human-readable name that does not change between seed runs

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

---

### User Story 4 - One dataset serves all four features (Priority: P3)

Someone working on the Goal Tracker uses the same seeded customers as someone working on Risk Scoring. When the Chatbot reports a customer's spending, it matches what the Goal Tracker shows for that same customer. No feature carries its own private copy of "the test customers".

**Why this priority**: The consistency payoff is what turns a set of personas into a baseline. It is last because the earlier stories are still valuable if only some features have adopted the shared set, and adoption can proceed feature by feature.

**Independent Test**: Pick one seeded persona, exercise it through all four features, and confirm each feature reflects the same underlying accounts, transactions, and goals with no contradictions.

**Acceptance Scenarios**:

1. **Given** a seeded persona, **When** the same customer is viewed through the Chatbot, Goal Tracker, Admin Control, and Risk Scoring features, **Then** all four reflect the same underlying account, transaction, and goal data
2. **Given** the shared dataset, **When** the four features' test setups are inspected, **Then** none of them defines its own duplicate persona for a scenario the shared dataset already covers
3. **Given** a change to a seeded persona's data, **When** the four features are exercised again, **Then** all four reflect the change consistently, with no feature reading a stale private copy

---

### Edge Cases

- **A test run mutates seeded data.** A scenario that transfers money, completes a goal, or restricts an account leaves the persona in a changed state. Re-applying the baseline will not undo this — only the explicit reset will — so any scenario that requires a clean starting state must name the reset as its first step.
- **The passage of real time.** A persona whose activity was anchored to fixed calendar dates would drift past the risk-scoring minimum-history window as months pass, silently flipping a "sufficient data" persona into an insufficient-data one. Relative anchoring (FR-009) prevents this, at the cost that literal seeded timestamps differ between environments seeded on different days.
- **A long-lived environment reset months after it was first seeded.** The reset re-anchors dates to the moment of the reset, so personas return to their documented classification rather than to their original literal dates. Any external record that captured literal timestamps from the earlier seeding becomes stale.
- **Partial seeding.** If seeding is interrupted, the environment must not be left with some personas present and others missing in a way that reads as a legitimate state. The result must be either a complete baseline or an obvious failure.
- **Re-running the seed on an already-seeded environment.** Must not produce duplicate personas or duplicate transaction sets.
- **A persona sits on a threshold boundary.** If the sparse persona's history lands exactly on the minimum-history cutoff, or the goal-saver's progress lands exactly on a rounding boundary, expected outcomes become ambiguous and the persona stops being a reliable baseline.
- **The role-definition story lands mid-flight.** Personas seeded against today's two roles must be remappable to the four target roles without redefining the personas themselves.
- **Seeding reaches a production-like environment.** Fictional customers appearing in a production dataset is a data-integrity incident, not a test inconvenience.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The baseline dataset MUST define a named set of personas that includes, at minimum: a salaried customer with regular activity, a customer actively saving toward a near-term goal, an operations user who manages account restrictions, and a customer with deliberately sparse data.
- **FR-002**: Each persona MUST be identifiable by a stable, human-readable name and a stable identifier that do not change between seed runs or between environments.
- **FR-003**: The salaried-customer persona MUST have recurring income and regular spending activity spanning at least the minimum history window that risk scoring requires to produce a score.
- **FR-004**: The goal-saving persona MUST have at least one active savings goal with a near-term target date and partial, non-boundary progress toward it.
- **FR-005**: The operations-user persona MUST hold privileges sufficient to place and lift account restrictions on at least one other seeded persona.
- **FR-006**: The sparse persona MUST have transaction history below the configured minimum-history threshold by a clear margin, and insufficient goal and spending history to answer Chatbot questions that require it, so that both the Chatbot fallback response and the risk-scoring insufficient-data status are reachable.
- **FR-007**: All four features (Chatbot, Goal Tracker, Admin Control, Risk Scoring) MUST draw their persona-based scenarios from this single shared dataset; no feature may maintain a private duplicate of a persona the shared dataset already covers.
- **FR-008**: Applying the baseline to a clean environment MUST produce identical persona data every time — same balances, same transaction sets, same goal progress, same resulting risk outcomes.
- **FR-009**: Seeded activity dates MUST be generated relative to the moment of seeding rather than as fixed calendar dates, so that each persona sits in the same position relative to time-sensitive thresholds — notably the risk-scoring minimum-history window and the goal-saver's near-term target date — no matter when the seed is applied.
- **FR-010**: Because seeded dates shift with the seeding moment, scenarios and expected outcomes MUST be expressed in terms of observable results and relative positions ("has more than the minimum history", "is roughly two-thirds toward the goal") and MUST NOT assert on literal timestamps.
- **FR-011**: Applying the baseline to an already-seeded environment MUST fill in only what is missing: it MUST NOT create duplicate personas, accounts, transactions, or goals, and MUST NOT overwrite data that is already present.
- **FR-012**: A separate, explicitly invoked reset action MUST restore every persona to its documented starting state, discarding changes made by previous runs. It MUST be documented as the prerequisite step for any QA run or demo that requires a guaranteed baseline, and MUST NOT run implicitly on environment startup.
- **FR-013**: The dataset MUST be accompanied by a catalogue that lists each persona, the scenarios it is intended to support, and the expected outcome for that persona in each of the four features.
- **FR-014**: Personas MUST be assigned roles from the roles the platform currently recognizes. Where a persona's intended role has no current platform equivalent, the intended role MUST be recorded in the catalogue as provisional, so the mapping can be completed when the role-definition story lands without redefining the personas.
- **FR-015**: Seeded data MUST be fictional and MUST NOT contain, or be derived from, real customer information.
- **FR-016**: Persona amounts, dates, and progress values MUST be chosen to sit clear of thresholds and rounding boundaries, so each persona's expected outcome in each feature is unambiguous.
- **FR-017**: The baseline MUST apply either completely or not at all; a partially applied baseline MUST be reported as a failure rather than left in place as an apparently valid state.

### Security & Configuration Requirements *(mandatory for endpoint/proxy/auth changes)*

- **SCR-001**: Seeding MUST be restricted to non-production environments and MUST be inert in a production configuration.
- **SCR-002**: Seeded credentials MUST be usable only in non-production environments and MUST NOT grant any access beyond what the persona's role legitimately carries.
- **SCR-003**: The operations-user persona's elevated privileges MUST be scoped to the seeded environment and MUST NOT require weakening any existing endpoint authorization rule to make the persona work.

### Key Entities *(include if feature involves data)*

- **Persona**: A named, reusable test identity representing one primary user type. Carries an intended purpose, a role assignment (with a provisional target role where applicable), and the set of scenarios it is meant to support.
- **Persona Catalogue**: The human-readable record of the personas — who each one is, what they are for, and what each of the four features is expected to report for them. The reference a tester or presenter consults to pick the right persona.
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
- **SC-005**: Zero feature-specific duplicate personas remain for scenarios the shared dataset covers.
- **SC-006**: A tester unfamiliar with the dataset can identify the correct persona for a given scenario in under 1 minute using the catalogue alone.
- **SC-007**: The same scenario run on two independently prepared environments produces the same result, with no environment-specific setup notes required to reconcile them.
- **SC-008**: Six months after the dataset is defined, every persona still produces the outcome its catalogue entry documents, without anyone having adjusted the data for the passage of time.

## Assumptions

Two open questions were resolved by default rather than by decision, because the specification could not be completed without them. Both are recorded here so they can be overridden in `/speckit-clarify` without reworking the rest of the spec.

- **Date anchoring (FR-009, FR-010) — resolved as: relative to the moment of seeding.** Chosen over fixed calendar dates because risk scoring derives its sufficiency window from the current date, so fixed dates would silently reclassify the salaried persona as insufficient-data once enough months elapsed. The cost is that literal seeded timestamps differ between environments, so assertions must target outcomes rather than dates. Rejected alternative: a pinnable "as of" clock, which would give both properties but requires controllable-time machinery beyond defining personas.
- **Re-seed semantics (FR-011, FR-012) — resolved as: fill in what is missing, with a separate explicit reset.** Chosen over restore-on-every-apply because the latter would silently destroy a developer's in-progress local work each time the environment started. The cost is that the guaranteed baseline becomes a deliberate step QA and demos must remember rather than an automatic property.
- The three personas named in the source BRD (Section 1) are the salaried customer, the goal-saving customer, and the operations user. The BRD is not held in this repository, so those descriptions are taken from the feature request itself.
- The sparse persona is a fourth, distinct persona (a newly onboarded customer) rather than one of the three primary personas made data-poor, so that each primary persona can fully exercise its own scenario.
- "All four features" means the Chatbot, Goal Tracker, Admin Control, and Risk Scoring features.
- Seeding applies to non-production environments only: local development, CI, QA, and demo. Production is explicitly out of scope.
- The platform currently recognizes two roles, a customer role and an administrator role. Personas are seeded against these two roles, and their intended mapping to the four target roles is recorded as provisional.
- The minimum-history window that separates a scorable customer from an insufficient-data one is an existing configured value; this feature consumes it rather than defining it, and positions personas clear of it in both directions.
- This feature introduces no API request/response contract changes. If a triggered seeding or reset interface is later added, the constitution's contract and error-semantics requirements apply to that work, not to this one.
- Personas may be added to the dataset later; the four defined here are the minimum baseline, not a closed set.

## Dependencies

- **Role definition story** (Bank Administrator, Risk Analyst, Compliance/Audit Observer, Retail Customer): persona role assignment depends on it. If it has not landed when this work starts, personas are seeded against the two existing roles and the target mapping is recorded as provisional (FR-014).
- **CFG-06 — shared data contracts** for the account, transaction, and user/profile services. Explicitly out of scope here: this feature defines personas and their dataset, not the contracts those services expose.
- **Existing risk-scoring minimum-history configuration**: determines where the sparse persona must sit relative to the threshold.
