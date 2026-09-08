# Contract: Persona Catalogue

**Feature**: [../spec.md](../spec.md) | **Data model**: [../data-model.md](../data-model.md)

This feature exposes **no HTTP interface** — no controller, no DTO, no endpoint. The constitution's contract gates for request/response shapes therefore do not apply.

It does expose one interface, and this document is its contract: the catalogue file that four features' tests read expected values from. A change to this shape breaks consumers exactly as a DTO change would, so it is versioned by review rather than by a version field (FR-020 rules out a version number).

**File**: `backend/src/main/resources/personas/voltio-personas.yaml`

---

## Consumer contract

| Consumer | Reads | Guarantee relied on |
|---|---|---|
| `PersonaSeeder` | Everything except `expectations` | Offsets are relative; refs resolve within a persona |
| Feature tests (all four) | `key`, `login`, `expectations` | A persona named by `key` exists and matches its expectations |
| A person choosing a persona | `purpose`, `scenarios`, `displayName`, `login` | Readable without tooling |

Tests **must** reach this file through the `Personas` accessor, never by parsing it themselves. One parse path means a schema change is absorbed in one place (FR-017, FR-018).

---

## Schema

```yaml
personas:
  - key: string              # required, unique. Symbolic name; the reset scope unit.
    login: string            # required, unique. Reserved login identity (natural key).
    displayName: string      # required. Fictional person name.
    role: CUSTOMER | ADMIN   # required. Today's only two roles.
    provisionalRole: string  # optional. Intended target role; recorded, not enforced.
    purpose: string          # required. Why this persona exists. Never asserted on.
    scenarios: [string]      # required, >=1. Scenarios that touch this persona.

    accounts:                # required, >=1
      - ref: string          # required, unique within this persona
        type: CHECKING | SAVINGS | ...     # AccountType
        status: ACTIVE | FROZEN | CLOSED   # AccountStatus
        balance: decimal                   # scale 2
        dailyTransferLimit: decimal        # scale 2

    goals:                   # optional, may be empty
      - accountRef: string   # must match an accounts[].ref above
        name: string
        targetAmount: decimal
        targetDaysAhead: int # RELATIVE. Resolved against the run anchor.
        status: ACTIVE | ...

    transactions:            # optional, may be empty
      - accountRef: string   # must match an accounts[].ref above
        amount: decimal
        direction: CREDIT | DEBIT | TRANSFER
        status: SUCCESS | ...              # only SUCCESS counts for the chatbot
        daysAgo: int         # RELATIVE. Resolved against the run anchor.
        repeatMonthly: int   # optional, default 1. Expands to a monthly series.
        category: string
        description: string

    expectations:            # required. What the four features must report.
      riskStatus: CALCULATED | INSUFFICIENT_DATA
      riskLevel: LOW | MODERATE | ELEVATED | HIGH | null
      chatbotSufficientData: bool
      goalProgressPercent: decimal | null
      canManageRestrictions: bool
```

### Absent by design

- **No `accountId`, `accountNumber`, `customerId`, `userId`, `goalId`.** FR-002 forbids pinning generated identifiers; personas are resolved by `login`.
- **No absolute dates.** Every temporal field is an offset (FR-010). A field named with a date type is a contract violation.
- **No exact risk score.** Only `riskLevel`. See [../research.md](../research.md) R3.
- **No version, no changelog, no owners list.** Ruled out during clarification; catalogue validation is the notification mechanism (FR-020).

---

## Invariants

Violating any of these fails at load time, before seeding starts, so a malformed catalogue never produces a partial environment (FR-024).

| # | Invariant | Why |
|---|---|---|
| C1 | `key` unique across personas | Reset targets a key; ambiguity would reset the wrong data |
| C2 | `login` unique across personas | It is the natural key; duplicates break fill-in-missing detection |
| C3 | `login` matches the reserved convention | FR-003 — a seeded account must be recognizable on sight |
| C4 | Every `accountRef` resolves within the same persona | FR-015 — no data shared between personas, so no cross-persona ref |
| C5 | At most one goal per `accountRef` | Database constraint `uq_sg_customer_account` |
| C6 | At least one persona with `riskStatus: INSUFFICIENT_DATA` and `chatbotSufficientData: false` | FR-007 — deleting the sparse persona must fail the build, not silently drop edge-case coverage |
| C7 | At least one persona with `canManageRestrictions: true` | FR-006 — the operations user must exist |
| C8 | `riskLevel` is null iff `riskStatus` is `INSUFFICIENT_DATA` | Matches `RiskScoreResponse`, which omits level when data is insufficient |
| C9 | `goalProgressPercent` non-null iff the persona has ≥1 goal | Prevents an expectation that nothing produces |
| C10 | Decimal fields carry at most 2 decimal places | Columns are `precision 19, scale 2`; more would silently round |

---

## Worked example

Two personas, showing the sufficient and insufficient ends. Values are illustrative — final figures are a task-level choice constrained by FR-023 and verified by `PersonaCatalogueValidationTest`.

```yaml
personas:
  # ── Sufficient data: scorable, personalised, mid-band on every factor ──
  - key: salaried
    login: seed.salaried@voltio.test
    displayName: Dana Whitfield
    role: CUSTOMER
    provisionalRole: RETAIL_CUSTOMER
    purpose: >
      The ordinary case. Regular income, regular spending, enough history that
      risk scoring produces a real score and the chatbot personalises its answers.
      Use this persona whenever a scenario needs a customer who "just works".
    scenarios:
      - risk-scoring-happy-path
      - chatbot-personalised-spending
    accounts:
      - ref: main
        type: CHECKING
        status: ACTIVE
        balance: 4250.00
        dailyTransferLimit: 2000.00
    transactions:
      # 18 months of history: 6x the 3-month risk minimum, so this persona
      # cannot drift into INSUFFICIENT_DATA even if minMonths is raised.
      - accountRef: main
        amount: 3200.00
        direction: CREDIT
        status: SUCCESS
        daysAgo: 1
        repeatMonthly: 18
        category: Salary
        description: Monthly salary
      # >=3 spend transactions inside the chatbot's 30-day window.
      - accountRef: main
        amount: 640.00
        direction: DEBIT
        status: SUCCESS
        daysAgo: 3
        repeatMonthly: 18
        category: Rent
        description: Rent
      - accountRef: main
        amount: 210.00
        direction: DEBIT
        status: SUCCESS
        daysAgo: 8
        repeatMonthly: 18
        category: Groceries
        description: Weekly shop
      - accountRef: main
        amount: 95.00
        direction: DEBIT
        status: SUCCESS
        daysAgo: 15
        repeatMonthly: 18
        category: Transport
        description: Travel pass
    expectations:
      riskStatus: CALCULATED
      riskLevel: MODERATE
      chatbotSufficientData: true
      goalProgressPercent: null
      canManageRestrictions: false

  # ── Insufficient data: fails BOTH thresholds, with margin, in one persona ──
  - key: sparse
    login: seed.sparse@voltio.test
    displayName: Ilan Roscoe
    role: CUSTOMER
    provisionalRole: RETAIL_CUSTOMER
    purpose: >
      Newly onboarded. Deliberately data-poor so the fallback paths stay exercised:
      two transactions is one short of the chatbot's minimum of three, and ten days
      of history is nowhere near the risk window's three months. Two rather than
      zero on purpose - zero would pass both checks vacuously and would not catch a
      regression in the chatbot's count comparison.
    scenarios:
      - risk-scoring-insufficient-data
      - chatbot-fallback-response
    accounts:
      - ref: main
        type: CHECKING
        status: ACTIVE
        balance: 180.00
        dailyTransferLimit: 500.00
    transactions:
      - accountRef: main
        amount: 45.00
        direction: DEBIT
        status: SUCCESS
        daysAgo: 10
        category: Groceries
        description: Corner shop
      - accountRef: main
        amount: 22.50
        direction: DEBIT
        status: SUCCESS
        daysAgo: 10
        category: Transport
        description: Bus fare
    expectations:
      riskStatus: INSUFFICIENT_DATA
      riskLevel: null
      chatbotSufficientData: false
      goalProgressPercent: null
      canManageRestrictions: false
```

The remaining two personas — `goalSaver` (a goal at 45% progress, target 90 days out) and `operations` (role `ADMIN`, `canManageRestrictions: true`, with a `FROZEN` account to unfreeze) — follow the same shape and are defined during implementation.

---

## Breaking-change policy

Adding an optional field with a default is compatible. Renaming or removing a field, or changing a field's meaning, breaks every consumer at once — which is the intended behaviour: FR-020 makes catalogue validation the notification, so an incompatible change fails the four features' tests at the moment it is made rather than being announced by a version bump nobody reads.
