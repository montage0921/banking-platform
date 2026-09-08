# Quick Start: Shared Seed Personas Validation

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Contracts**: [contracts/persona-catalogue.md](contracts/persona-catalogue.md) | **Date**: 2026-09-07

How to prove the baseline works end to end. Six scenarios, one per user story plus the two guards. Each states what to run and what you should see.

---

## Prerequisites

- **Java 21** and the Maven wrapper at `backend/mvnw`
- **Primary datasource**: H2 file mode by default (`jdbc:h2:file:./data/digitalbankdb`) — no external database needed for any scenario below. QA environments on MySQL or PostgreSQL work identically; the seeder writes through JPA.
- **Seeding disabled by default.** Every scenario that seeds must turn it on explicitly. This is the point, not an inconvenience.
- **Not required**: the chatbot's PostgreSQL/pgvector store, a `GROQ_API_KEY`, or the MCP rates server. Scenario 3 asserts the chatbot's *data-sufficiency* decision, which is computed from transactions in `SavingsChatContextService` before any model call.

### Enabling seeding

```bash
# From the repo root
cd backend
./mvnw spring-boot:run -Dspring-boot.run.arguments=--app.seed.personas.enabled=true
```

Or via environment variable, which is how QA and demo environments should do it:

```bash
export APP_SEED_PERSONAS_ENABLED=true
```

---

## Scenario 1 — The personas exist (User Story 1, P1)

**Setup**: start from a clean database.

```bash
cd backend
rm -rf ./data/digitalbankdb*          # clean slate
./mvnw spring-boot:run -Dspring-boot.run.arguments=--app.seed.personas.enabled=true
```

**Verify** at `http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:file:./data/digitalbankdb`, user `sa`, no password):

```sql
SELECT username, is_active FROM users WHERE username LIKE 'seed.%' ORDER BY username;
```

**Expect** all four reserved logins present: `seed.goalsaver@voltio.test`, `seed.operations@voltio.test`, `seed.salaried@voltio.test`, `seed.sparse@voltio.test`.

Then confirm each carries the data its scenario needs:

```sql
SELECT u.username, COUNT(DISTINCT a.account_id) AS accounts,
       COUNT(t.transaction_id) AS txns
FROM users u
  JOIN account a ON a.customer_id = u.customer_id
  LEFT JOIN bank_transaction t ON t.account_id = a.account_id
WHERE u.username LIKE 'seed.%'
GROUP BY u.username ORDER BY u.username;
```

**Expect** the salaried persona with many transactions (18 months of expanded monthly series) and the sparse persona with exactly 2. Compare against [contracts/persona-catalogue.md](contracts/persona-catalogue.md) — the catalogue is the authority on what each row should be.

Automated equivalent:

```bash
cd backend && ./mvnw test -Dtest=PersonaSeedingTest
```

✅ **Pass**: four logins, each with the accounts and transaction volume its catalogue entry describes, and no manual data entry performed.

---

## Scenario 2 — Both insufficient-data paths fire on one persona (User Story 2, P2)

This is the scenario worth running by hand, because it crosses two independently configured thresholds. See [research.md](research.md) R2 for why the numbers are what they are.

```bash
cd backend
./mvnw test -Dtest=SparsePersonaThresholdTest
```

**Expect** assertions on both consumers for `seed.sparse@voltio.test`:

- **Risk Scoring** returns `calculateStatus = INSUFFICIENT_DATA`, code `RISK_SCORE_INSUFFICIENT_DATA`, and **no** `score`, `level` or `explain` in the payload.
- **Chatbot** returns `SpendCategorySummary.sufficientData = false` — 2 qualifying transactions against `min-for-personalization: 3`.
- **Margin checks**: the persona's earliest transaction is far newer than the 3-month risk window, and its qualifying count is below 3 rather than equal to it.

✅ **Pass**: one persona, both fallback paths, neither sitting on a boundary.

⚠️ If only one of the two fails, the persona has drifted into the gap between the thresholds — the failure message names which threshold was crossed.

---

## Scenario 3 — Reproducibility across runs and across time (User Story 3, P2)

```bash
cd backend
./mvnw test -Dtest=PersonaDeterminismTest
```

**Expect**: seeding a clean database twice produces identical balances, transaction counts, goal progress, and risk outcomes both times. Literal timestamps will differ between runs — that is correct and required (FR-011); the test asserts on outcomes and relative positions, never on dates.

**Verify the time-drift property by hand** — the one thing a test run on a single day cannot show you:

```sql
-- The salaried persona's earliest transaction, relative to the 3-month risk window.
SELECT MIN(t.timestamp) AS earliest, DATEADD('MONTH', -3, CURRENT_TIMESTAMP) AS risk_cutoff
FROM bank_transaction t
  JOIN account a ON a.account_id = t.account_id
  JOIN users u ON u.customer_id = a.customer_id
WHERE u.username = 'seed.salaried@voltio.test';
```

**Expect** `earliest` well before `risk_cutoff` — roughly 18 months of margin against a 3-month requirement. Because dates are anchored to the seeding moment, this stays true whenever you run it, which is what SC-008 asks for.

✅ **Pass**: same outcomes across runs; the salaried persona is nowhere near the risk cutoff regardless of when you look.

---

## Scenario 4 — Reset is scoped, and re-seeding is not a reset (User Story 3)

```bash
cd backend
./mvnw test -Dtest=PersonaResetScopeTest
```

**Expect** three behaviours, which are easy to conflate:

| Action | Effect |
|---|---|
| Mutate persona A, then **re-apply the baseline** | A stays mutated. Fill-in-missing does not overwrite (FR-012). |
| Mutate personas A and B, then **reset A only** | A returns to baseline; **B stays mutated** (FR-014, SC-010). |
| Re-apply the baseline twice on a seeded database | No duplicate users, accounts, transactions, or goals. |

The middle row is the one that matters in a shared QA environment: it is what lets two people work at once.

✅ **Pass**: reset restores exactly its named personas and nothing else; re-seeding never duplicates and never overwrites.

---

## Scenario 5 — One dataset, four features, no drift (User Story 4, P3)

```bash
cd backend
./mvnw test -Dtest=PersonaCatalogueValidationTest
```

**Expect** every persona's seeded rows checked against its `expectations` block: risk status and level, chatbot sufficiency, goal progress recomputed from account balance ÷ target, and restriction-management capability. Also checked: no duplicate account identifiers ([research.md](research.md) R5), no value sitting on a risk band boundary (FR-023).

**Then prove drift is caught.** Edit `voltio-personas.yaml`, change the salaried persona's balance materially, and re-run. The test **must fail**. If it passes, the catalogue has stopped being the source of truth and FR-017 is violated.

```bash
cd backend && ./mvnw test    # full suite: catalogue validation runs alongside all four features
```

✅ **Pass**: all four features agree with the catalogue, and a deliberate edit breaks the build.

---

## Scenario 6 — The guards refuse (SCR-001, SC-011)

```bash
cd backend
./mvnw test -Dtest=SeedGuardTest
```

**Expect** both guards proven independently:

| Configuration | Result |
|---|---|
| Flag absent (the default) | No seeding. The runner bean is never created. |
| Flag `false` | No seeding. |
| Flag `true`, no production signal | Seeds. |
| Flag `true`, **profile `prod` active** | **Refuses.** Startup fails with a message naming the guard. |
| Flag `true`, `app.seed.personas.environment=production` | **Refuses.** |

The last two are the ones that matter. A refusal must be distinguishable from a failure (SCR-002) — an operator seeing an environment with no personas must be able to tell "deliberately prevented" from "broken".

The default-off guarantee is also asserted automatically, including that the runner bean is
never created:

```bash
cd backend && ./mvnw test -Dtest=SeedDefaultOffTest
```

**Check by hand as well**, since this is the guard everything else rests on:

```bash
cd backend
rm -rf ./data/digitalbankdb*
./mvnw spring-boot:run          # note: NO enabling flag
```

```sql
SELECT COUNT(*) FROM users WHERE username LIKE 'seed.%';
```

**Expect** `0`. A fresh application does not seed itself.

✅ **Pass**: off by default, refuses production loudly, seeds only when told twice over.

---

## Full validation

```bash
cd backend && ./mvnw test
```

All six scenarios plus the pre-existing suite - 730 tests in total.

**Three failures are expected and are not caused by this feature**, verified by running them
on a clean tree before any of this work landed:

| Test | Cause |
|---|---|
| `RiskScoreServiceInsufficientDataTest.omitsScoreAndBandFromSerializedPayload` | Asserts a JSON field `status`; the DTO serialises it as `calculateStatus` |
| `SavingsGoalServiceTest.deleteGoal_setsDeletedAt_doesNotHardDelete` | `SavingsGoalService.deleteGoal` calls `repository.delete()`, a hard delete |
| `SavingsGoalServiceTest.createGoal_moreThanTwoDecimalPlaces_throwsInvalidTargetAmount` | Backend decimal-place validation is missing, so the call NPEs instead of throwing |

Every seeding test passes. Catalogue validation runs here by design (FR-020): a change to a persona that invalidates any of the four features' expectations fails at this point, which is the only change-notification mechanism this feature has — no version number, no changelog.

---

## Adopting the personas in a feature's tests

Reach for the accessor, never the YAML and never a hardcoded login:

```java
// Correct: resolves through the single parse path.
var persona = Personas.byKey("sparse");
var customerId = Personas.customerIdOf(persona);
assertThat(riskScoreService.calculate(customerId).getCalculateStatus())
        .isEqualTo(persona.expectations().riskStatus());
```

Two rules, both from FR-017:

- **Never restate an expected value** in a test. Read it from `expectations`, so a catalogue change fails loudly rather than leaving two disagreeing copies.
- **Never hardcode an identifier.** `customerId` and `accountId` are generated and deliberately not pinned (FR-002); resolve from the login identity.

When you migrate a feature off its private fixtures, delete them in the same change — leaving both is the duplication FR-008 forbids. [specs/001-savings-goals/quickstart.md](../001-savings-goals/quickstart.md) contains hand-rolled "assume test customer exists" SQL of exactly the kind this baseline replaces, and is a good first candidate.
