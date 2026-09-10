# Phase 1 Data Model: Shared Seed Personas & Baseline Dataset

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Research**: [research.md](./research.md)

This feature adds **no new persisted entity and no schema change**. It populates existing tables. What is modelled here is the catalogue structure (a resource, not a table) and the exact shape of the rows each persona produces in the existing schema.

---

## Part 1 — Catalogue model (new, not persisted)

Records bound from [contracts/persona-catalogue.md](./contracts/persona-catalogue.md). All are immutable Java records under `com.group1.banking.seed`.

### `PersonaCatalogue`

| Field | Type | Notes |
|---|---|---|
| `personas` | `List<Persona>` | Order is presentation only; seeding is order-independent |

**Validation**: at least one persona; every `key` unique; every `login` unique; at least one persona with `expectations.riskStatus = INSUFFICIENT_DATA` (enforces FR-007 structurally, so deleting the sparse persona fails the build rather than quietly removing edge-case coverage).

### `Persona`

| Field | Type | Notes |
|---|---|---|
| `key` | `String` | Stable symbolic name (`salaried`, `goalSaver`, `operations`, `sparse`). The reset scope unit (FR-014) and how tests name a persona. |
| `login` | `String` | The reserved login identity — the natural business key (FR-002). Must match the convention in FR-003. |
| `displayName` | `String` | Fictional person name (FR-022) |
| `role` | `RoleName` | Exactly one of the four recognized roles; no provisional second role (FR-021) |
| `purpose` | `String` | Why this persona exists; read by humans, never asserted on |
| `scenarios` | `List<String>` | Which scenarios touch this persona (FR-016), so overlap is visible before a reset |
| `accounts` | `List<SeedAccount>` | |
| `goals` | `List<SeedGoal>` | Empty for personas without goals |
| `transactions` | `List<SeedTransaction>` | |
| `expectations` | `Expectations` | What the four features must report (FR-017) |

### `SeedAccount`

| Field | Type | Notes |
|---|---|---|
| `ref` | `String` | Local handle so goals and transactions can point at an account without knowing its generated id |
| `type` | `AccountType` | Existing enum |
| `status` | `AccountStatus` | `ACTIVE` / `FROZEN` / `CLOSED` |
| `balance` | `BigDecimal` | Scale 2, matching the column |
| `dailyTransferLimit` | `BigDecimal` | Scale 2 |

`accountId` and `accountNumber` are **absent by design** — assigned at seed time by the shared helper (see [research.md](./research.md) R5), never pinned.

### `SeedGoal`

| Field | Type | Notes |
|---|---|---|
| `accountRef` | `String` | Must match a `SeedAccount.ref` on the same persona |
| `name` | `String` | |
| `targetAmount` | `BigDecimal` | Scale 2 |
| `targetDaysAhead` | `int` | Relative offset (FR-010), resolved to `targetDate` |
| `status` | `SavingsGoalStatus` | |

**Note on progress**: `SavingsGoal.currentBalance` and `progressPercentage` are `@Transient` — derived from the linked account's balance, not stored. Goal progress is therefore controlled by choosing `balance` and `targetAmount` together; the catalogue records the *intended* percentage under `expectations` and the validation test recomputes it. The `uq_sg_customer_account` constraint means **one goal per account per customer**, so a persona needing two goals needs two accounts.

### `SeedTransaction`

| Field | Type | Notes |
|---|---|---|
| `accountRef` | `String` | Must match a `SeedAccount.ref` |
| `amount` | `BigDecimal` | Scale 2 |
| `direction` | `TransactionDirection` | `CREDIT` / `DEBIT` / `TRANSFER` |
| `status` | `TransactionStatus` | Only `SUCCESS` counts toward the chatbot threshold |
| `daysAgo` | `int` | Relative offset (FR-010) |
| `category` | `String` | Drives the chatbot's category breakdown; blank becomes `Uncategorised` |
| `description` | `String` | |

**Recurrence**: a `repeatMonthly` count may expand one entry into a monthly series, so the salaried persona's 18 months of income is a few lines rather than eighteen. Expansion happens at load time against the single run anchor.

### `Expectations`

| Field | Type | Asserted against |
|---|---|---|
| `riskStatus` | `RiskScoreStatus` | `OK` or `INSUFFICIENT_DATA` |
| `riskLevel` | `RiskScoreLevel` | null |\| null | `LOW`/`MODERATE`/`ELEVATED`/`HIGH`; null when `INSUFFICIENT_DATA` |
| `chatbotSufficientData` | `boolean` | `SpendCategorySummary.sufficientData` |
| `goalProgressPercent` | `BigDecimal` \|| `riskLevel` | `RiskScoreLevel` | null | Recomputed from balance ÷ target |
| `canManageRestrictions` | `boolean` | Whether the persona can freeze/unfreeze another account |

Deliberately **no exact numeric risk score** — see [research.md](./research.md) R3. The level is asserted; the raw score is only checked for boundary proximity.

---

## Part 2 — Existing entities this feature writes

No column is added, altered, or dropped. `ddl-auto=update` performs no migration for this feature, and no file is added to [db/migration/](backend/src/main/resources/db/migration/).

| Entity | Table | Identifier strategy | Seeding note |
|---|---|---|---|
| [User](backend/src/main/java/com/group1/banking/entity/User.java) | `users` | UUID in `@PrePersist` | `username` is the reserved login identity and is `unique` — this uniqueness is what makes fill-in-missing (FR-012) detectable. `passwordHash` must be a real hash of a documented non-production password. |
| [Customer](backend/src/main/java/com/group1/banking/entity/Customer.java) | `customers` | `IDENTITY` | Linked to `User` by `User.customerId` (a plain `Long`, not a mapped association) |
| [Account](backend/src/main/java/com/group1/banking/entity/Account.java) | `account` | **Manually assigned** | Uses the shared `count() + 1000` helper; `@Version` starts at 0 |
| [Transaction](backend/src/main/java/com/group1/banking/entity/Transaction.java) | `bank_transaction` | `String`, manually assigned | `externalTransactionId` is `unique` and auto-filled in `@PrePersist`; `timestamp` must be set explicitly or `@PrePersist` defaults it to now, defeating the anchor |
| [SavingsGoal](backend/src/main/java/com/group1/banking/entity/SavingsGoal.java) | `savings_goals` | `IDENTITY` | `uq_sg_customer_account` limits one goal per account |
| [RiskScore](backend/src/main/java/com/group1/banking/entity/RiskScore.java) | — | `IDENTITY` | **Not seeded.** Scores are computed on demand; seeding them would fabricate the very output under test. |

### Relationship map

```text
User (username = reserved login identity)
 └─ customerId ──→ Customer
                    ├─ accounts ──→ Account (1..n)
                    │                ├─ transactions ──→ Transaction (0..n)
                    │                └─ savings goal ──→ SavingsGoal (0..1)
                    └─ riskScoreHistory ──→ RiskScore  [computed, never seeded]
```

`Customer.accounts` is `cascade = ALL` and `riskScoreHistory` is `cascade = ALL, orphanRemoval = true`, which matters for reset: deleting a seeded customer cascades to accounts and risk history, but **not** to transactions or savings goals, which are owned by `Account` without a cascading collection. Reset must delete those explicitly, in dependency order.

---

## Part 3 — Persona state and lifecycle

The unit of state is the persona (FR-015: no data shared between personas, so one reset never disturbs another).

```text
        ┌──────────────┐
        │  ABSENT      │  no User with this login exists
        └──────┬───────┘
               │  seed (guards passed, FR-012 fill-in-missing)
               ▼
        ┌──────────────┐
        │  BASELINE    │  matches its catalogue entry exactly
        └──────┬───────┘
               │  a test or demo transfers money, completes a goal,
               │  freezes an account…
               ▼
        ┌──────────────┐
        │  MUTATED     │  still present; no longer matches the catalogue
        └──────┬───────┘
               │  reset(personaKey) — explicit, scoped, never automatic
               ▼
            BASELINE
```

Transitions worth stating because they are easy to get wrong:

- **ABSENT → BASELINE** is the only transition seeding performs. Applying the baseline to a persona already in `MUTATED` leaves it `MUTATED` — that is FR-012's fill-in-missing rule, and it is why US3 scenario 2 requires reset rather than re-seed.
- **Partial application is not a state** (FR-024). A persona is created inside one transaction; a failure rolls back to `ABSENT` rather than leaving a half-built customer that reads as legitimate.
- **Reset is delete-then-recreate**, not diff-and-patch. Re-anchoring dates to the reset moment is the point (see [research.md](./research.md) R4), and patching would have to reconcile arbitrary mutations against a moving anchor for no gain.
- **Reset re-anchors.** A persona reset in March and again in September has different literal timestamps and the same classification. FR-011 forbids asserting on the former.

### Validation rules carried from the spec

| Rule | Source | Enforced by |
|---|---|---|
| Sparse persona below both thresholds with margin | FR-007 | `SparsePersonaThresholdTest` |
| Salaried persona above the risk minimum-history window | FR-004 | `PersonaCatalogueValidationTest` |
| No value on a band boundary | FR-023 | `PersonaCatalogueValidationTest` (margin check) |
| No duplicate account identifiers after seeding | [research.md](./research.md) R5 | `PersonaCatalogueValidationTest` |
| Seeded data matches its catalogue entry | FR-019 | `PersonaCatalogueValidationTest` |
| One persona's reset leaves others untouched | FR-014, FR-015 | `PersonaResetScopeTest` |
| Same catalogue → same outcomes on repeat runs | FR-009 | `PersonaDeterminismTest` |
