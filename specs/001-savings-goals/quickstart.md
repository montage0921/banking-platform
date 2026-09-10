# Quick Start: Savings Goal Tracker Validation

**Status**: ✅ Ready for Validation  
**Date**: June 5, 2026  
**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Contracts**: [contracts/](contracts/)

---

## Prerequisites

### Backend Setup

- ✅ Database: MySQL 8.0+ with Voltio schema (customers, account, users tables exist)
- ✅ Java: Spring Boot 3.x application running on http://localhost:8080
- ✅ Auth: Valid JWT token for authenticated requests
- ✅ Migration: V001\_\_create_savings_goals.sql applied (savings_goals table exists)

### Frontend Setup

- ✅ Node.js / Vite application running on http://localhost:5173
- ✅ React hooks and components compiled
- ✅ Axios configured with proxy to backend
- ✅ Both Classic and Neon themes available

### Test Data

Use the shared seeded personas. Do not hand-build a customer for this feature - the
identifiers in an ad-hoc fixture stop matching the moment anyone rebuilds their
database, and every feature ends up maintaining its own slightly different copy.

Enable seeding and start the backend:

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.arguments=--app.seed.personas.enabled=true
```

The persona for this feature is **`goalSaver`** - `seed.goalsaver@voltio.test` - who has
a SAVINGS account carrying an active goal at 45% progress with a target date 90 days
out, plus a separate CHECKING account for everyday activity. Its full definition,
including the expected progress percentage, lives in
[`backend/src/main/resources/personas/voltio-personas.yaml`](../../backend/src/main/resources/personas/voltio-personas.yaml);
the schema is documented in
[the persona catalogue contract](../004-shared-seed-personas/contracts/persona-catalogue.md).

```sql
-- Resolve the persona by its reserved login. Never hardcode customer_id or account_id:
-- those are generated, and deliberately not pinned.
SELECT u.user_id, u.customer_id
FROM users u
WHERE u.username = 'seed.goalsaver@voltio.test';

SELECT a.account_id, a.account_number, a.balance, a.account_type, a.status
FROM account a
  JOIN users u ON u.customer_id = a.customer_id
WHERE u.username = 'seed.goalsaver@voltio.test';
```

Log in as the persona with the password documented on `PersonaSeeder.SEED_PASSWORD`.

If a scenario below leaves the goal in a changed state, restore just this persona
rather than wiping the environment - someone else may be working in it:

```java
resetService.reset("goalSaver");   // leaves every other persona untouched
```

**Writing tests against this persona**: read expected values from the catalogue through
the `Personas` accessor rather than restating them here. A number written in two places
is a number that will disagree with itself.

### Account mapping for the scenarios below

The scenarios reference three accounts belonging to the `goalSaver` persona. Resolve each by
its role, never by a hardcoded id - the ids are generated and deliberately not pinned:

| Referred to below as | goalSaver account | Baseline state |
|---|---|---|
| **the no-goal account** | the CHECKING account with no savings goal | no goal - the "Add a goal" prompt shows |
| **the overdue-goal account** | the SAVINGS account holding "Holiday fund" | goal OVERDUE, 75% progress, target date 30 days past |
| **the active-goal account** | the SAVINGS account holding "Emergency fund" | goal IN_PROGRESS, 45% progress, target date 90 days ahead |

**These scenarios mutate state and run in sequence** - Scenario 1 creates a goal that later
scenarios read, and Scenario 4 deletes one. Restore the baseline before each full pass, and
scope it so nobody else's work is touched:

```java
resetService.reset("goalSaver");
```

---

## Runnable Validation Scenarios

### Scenario 1: Create First Goal (Happy Path)

**Objective**: Create a savings goal for account with no goal

**Preconditions**:

- Authenticated as test customer (JWT token obtained)
- Account the no-goal account exists, status=ACTIVE, balance=1000.00, has no goal

**Steps**:

1. **Navigate to Accounts page**
   - Open http://localhost:5173/accounts
   - Verify account list renders
   - Verify the no-goal account shows "Add a goal" prompt

2. **Click "Add a goal" under the no-goal account**
   - Modal opens with 3-question form
   - Q1 dropdown shows: Emergency Fund, Travel, Tuition, Home, Car, Retirement, Other

3. **Select "Travel" from dropdown**
   - Dropdown field updates to show "Travel"

4. **Enter target amount "5000.00" in Q2**
   - Input accepts numeric entry
   - Format shows as "$5000.00" (if UX requires)

5. **Enter target date "2026-12-31" in Q3**
   - Date picker opens; select Dec 31, 2026
   - Date field updates to show "2026-12-31"

6. **Review screen**
   - Shows: "Travel", "$5000.00", "2026-12-31"
   - Button: "Confirm"

7. **Click "Confirm"**
   - POST /accounts/{no-goal account id}/goals sent with goal_name="Travel", target_amount=5000.00, a target date about a year ahead
   - Response 201 received with goal_id=1, progress_percentage=20.00, status="IN_PROGRESS", time_remaining_days=209

**Expected Outcome**:

- Goal created successfully ✅
- Goal card renders below account with:
  - Goal name: "Travel"
  - Progress bar: 20% filled (1000 / 5000)
  - Text: "20.00% of $5000.00"
  - Time: "209 days remaining"
  - Status badge: None (IN_PROGRESS doesn't show badge)

---

### Scenario 2: View Goal with Overdue Status

**Objective**: Display a goal past its deadline with overdue badge

**Preconditions**:

- Authenticated as test customer
- Goal already exists for the overdue-goal account with a target date already in the past, balance=800.00, target_amount=3000.00

**Steps**:

1. **Navigate to Accounts page**
   - Open http://localhost:5173/accounts
   - Verify account list renders

2. **Select account the overdue-goal account**
   - Goal card displays with:
     - Goal name: whatever was saved (e.g., "Emergency Fund")
     - Progress bar: 26.67% filled (800 / 3000)
     - Time: "Overdue" or "Overdue by XX days"
     - Status badge: OVERDUE (amber/red color)

3. **Verify UI theme consistency**
   - Switch theme: Settings → Theme → Neon (if available)
   - Goal card re-renders with Neon colors
   - Progress bar, badge, text all visible and legible in Neon theme
   - Switch back to Classic
   - Goal card re-renders with Classic colors

**Expected Outcome**:

- Overdue goal displayed with OVERDUE badge ✅
- Progress bar visible at 26.67% ✅
- Theme-specific styling applied in both Classic and Neon ✅
- Goal not hidden or archived ✅

---

### Scenario 3: Edit Goal (Change Amount & Date)

**Objective**: Edit an existing goal and verify progress recalculates

**Preconditions**:

- Authenticated as test customer
- Goal exists: goal_name="Travel", target_amount=5000.00, a target date about a year ahead, balance=1000.00

**Steps**:

1. **Navigate to goal card for the no-goal account**
   - Open Accounts page
   - Goal displays with 20% progress

2. **Click "Edit" button on goal card**
   - GET /accounts/100/goals/2 called
   - Response returns full goal with all fields
   - Edit form opens with pre-populated fields:
     - Q1 dropdown: "Travel" selected
     - Q2 input: "5000.00"
     - Q3 date picker: "2026-12-31"

3. **Change target amount to 6000.00**
   - Q2 input clears and receives "6000.00"

4. **Change target date to 2027-03-01**
   - Q3 date picker updates to "2027-03-01"

5. **Click "Save"**
   - PUT /accounts/{no-goal account id}/goals/{goal id} sent with goal_name="Travel", target_amount=6000.00, a later target date
   - Response 200 received with:
     - progress_percentage = (1000 / 6000) \* 100 = 16.67
     - time_remaining_days = 300 (new date minus today)
     - status = "IN_PROGRESS"
     - updated_at = new timestamp

**Expected Outcome**:

- Goal updated successfully ✅
- Goal card re-renders with:
  - Progress bar: 16.67% (reduced from 20%) ✅
  - Time: 300 days (increased from 209) ✅
  - Status: IN_PROGRESS (unchanged) ✅
- updated_at timestamp updated ✅

---

### Scenario 4: Delete Goal (Soft Delete)

**Objective**: Delete a goal and verify account returns to "Add a goal" state

**Preconditions**:

- Authenticated as test customer
- Goal exists for the active-goal account, its goal id

**Steps**:

1. **Navigate to goal card for the active-goal account**
   - Goal displays (e.g., "Home", 0% progress, 0 days remaining)

2. **Click "Delete" button**
   - Confirmation modal appears:
     - "Are you sure you want to delete this goal? This cannot be undone."
     - Buttons: "Cancel", "Delete"

3. **Click "Delete" in confirmation modal**
   - DELETE /accounts/102/goals/3 called
   - Response 204 No Content
   - Backend sets deleted_at = NOW()

4. **Verify UI state**
   - Goal card disappears
   - Account the active-goal account returns to "Add a goal" prompt state

5. **Verify database state** (optional backend validation)
   - Query: SELECT \* FROM savings_goals WHERE goal_id = 3;
   - Result: deleted_at IS NOT NULL ✅ (soft delete)
   - Row not hard-deleted ✅

**Expected Outcome**:

- Goal soft-deleted (deleted_at set) ✅
- UI reverts to "Add a goal" state ✅
- Audit log entry created ✅

---

### Scenario 5: Account Balance Change → Progress Auto-Updates

**Objective**: Verify progress recalculates when account balance changes (transaction occurs)

**Preconditions**:

- Authenticated as test customer
- Goal exists for the no-goal account: target_amount=5000.00, progress_percentage=20% (balance=1000.00)
- Account the no-goal account balance will be increased via transaction

**Steps**:

1. **View goal card**
   - the no-goal account goal displays: "20% of $5000.00"

2. **Simulate account transaction** (test data injection or API call)
   - POST /accounts/100/deposit (or similar)
   - New balance: 3000.00

3. **Refresh page** (or poll if real-time)
   - GET /accounts/100/goals called
   - Response returns:
     - current_balance = 3000.00 (updated from account.balance)
     - progress_percentage = (3000 / 5000) \* 100 = 60.00
     - status = "IN_PROGRESS"

4. **Verify UI updates**
   - Goal card re-renders with:
     - Progress bar: 60% filled (3 of 5 segments) ✅
     - Text: "60.00% of $5000.00" ✅
     - Status: unchanged (still IN_PROGRESS) ✅

**Expected Outcome**:

- Progress auto-updates to 60% ✅
- No manual refresh required by user (once real-time implemented) ✅
- Balance source verified as account.balance ✅

---

### Scenario 6: Achieve Goal (Balance Reaches Target)

**Objective**: Verify status changes to ACHIEVED when balance ≥ target_amount

**Preconditions**:

- Goal exists: target_amount=5000.00, current progress=60% (balance=3000.00)

**Steps**:

1. **Simulate deposit to reach/exceed target**
   - Update account balance to 5000.00 or higher (e.g., 5500.00)

2. **Refresh goal view**
   - GET /accounts/100/goals called
   - Response returns:
     - current_balance = 5500.00
     - progress_percentage = (5500 / 5000) \* 100 = 110.00 → capped at 100.00
     - status = "ACHIEVED"

3. **Verify UI updates**
   - Progress bar: 100% filled (full) ✅
   - Text: "100% of $5000.00" ✅
   - Status badge: ACHIEVED (green, or theme-specific color) ✅
   - Edit/Delete buttons still available ✅

**Expected Outcome**:

- Status correctly calculated as ACHIEVED ✅
- Progress percentage capped at 100% ✅
- Badge renders with ACHIEVED styling ✅

---

### Scenario 7: Error Case - Duplicate Goal

**Objective**: Verify 409 Conflict when attempting to create second goal for same account

**Preconditions**:

- Goal already exists for the no-goal account
- Authenticated user attempts to create another goal for the no-goal account

**Steps**:

1. **Click "Add a goal" on the no-goal account**
   - Modal opens again (button visible despite existing goal) — BUG or UX choice?
   - OR button disabled and shows "Edit goal" instead — expected UX

2. **Attempt to POST /accounts/{no-goal account id}/goals** (if button exists)
   - Request sent with goal_name="Vacation", target_amount=3000.00, a target date about a year ahead
   - Response 409 Conflict:
     ```json
     {
       "code": "GOAL_ALREADY_EXISTS",
       "message": "This account already has a goal. Edit or delete it first."
     }
     ```

3. **Verify error handling**
   - Error message displayed to user: "This account already has a goal. Edit or delete it first." ✅
   - Offer user options: "Edit existing goal" or "Delete and create new" ✅

**Expected Outcome**:

- 409 error returned ✅
- User-facing error message shown ✅
- No duplicate goal created ✅

---

### Scenario 8: Error Case - Invalid Target Amount

**Objective**: Verify 400 Bad Request when target_amount ≤ 0

**Preconditions**:

- Creating new goal for the active-goal account (no goal yet)

**Steps**:

1. **Fill goal form**
   - Q1: "Travel"
   - Q2: "-100.00" (invalid)
   - Q3: "2026-12-31"

2. **Click "Confirm"**
   - POST /accounts/102/goals sent
   - Response 400 Bad Request:
     ```json
     {
       "code": "INVALID_TARGET_AMOUNT",
       "message": "Target amount must be greater than $0"
     }
     ```

3. **Verify error handling**
   - Form shows error below Q2 input: "Target amount must be greater than $0" ✅
   - Form does NOT submit ✅
   - User can correct input and retry ✅

**Expected Outcome**:

- 400 error returned ✅
- Input validation error shown ✅
- Goal not created ✅

---

### Scenario 9: Bulk Goal Fetch (Multiple Accounts)

**Objective**: Verify GET /customers/{customer_id}/goals returns all goals for customer

**Preconditions**:

- Customer has multiple accounts with goals:
  - the no-goal account: goal "Travel", target=$5000, progress=60%
  - the overdue-goal account: goal "Emergency Fund", target=$3000, progress=26.67%, OVERDUE
  - the active-goal account: no goal yet

**Steps**:

1. **Navigate to Accounts page**
   - Single GET /customers/1/goals call made (bulk fetch)

2. **Verify response contains 2 goals** (the no-goal account and the overdue-goal account, not the active-goal account)
   - Array of 2 SavingsGoalResponse objects returned
   - Each includes: goal_id, account info, progress, status

3. **Verify goal cards render**
   - the no-goal account: "Travel" card with 60% progress
   - the overdue-goal account: "Emergency Fund" card with OVERDUE badge
   - the active-goal account: "Add a goal" prompt

**Expected Outcome**:

- Bulk fetch optimizes page load ✅
- All goals displayed correctly ✅
- Accounts with no goals show prompt ✅

---

### Scenario 10: Theme Validation (Classic & Neon)

**Objective**: Verify Savings Goal Tracker renders identically in both themes

**Preconditions**:

- Goal exists displaying all states (IN_PROGRESS, OVERDUE, ACHIEVED)

**Steps**:

1. **In CLASSIC theme**
   - View Accounts page with goals
   - Verify:
     - Progress bars use Classic colors (blue?)
     - Badges use Classic styling
     - Form inputs, buttons, dropdowns match Classic theme
     - Text contrast sufficient (WCAG AA)

2. **Switch to NEON theme**
   - Settings → Theme → Neon
   - View Accounts page
   - Verify:
     - Progress bars use Neon colors (bright/vibrant?)
     - Badges use Neon styling
     - Form inputs, buttons, dropdowns match Neon theme
     - Text contrast sufficient (WCAG AA)
     - Functionality unchanged (same interactions work)

3. **Switch back to CLASSIC**
   - Verify styling reverts
   - No broken state or residual Neon styles

**Expected Outcome**:

- Classic theme: Goals display correctly ✅
- Neon theme: Goals display correctly ✅
- Theme switching preserves functionality ✅
- No style conflicts or missing CSS ✅

---

## Acceptance Criteria Checklist

### Backend Implementation

- [ ] savings_goals table created with correct schema
- [ ] Foreign key constraints enforced
- [ ] UNIQUE(customer_id, account_id) constraint prevents duplicates
- [ ] Soft delete pattern working (deleted_at set, not hard-deleted)

### Backend Endpoints

- [ ] POST /accounts/{account_id}/goals — creates goal, returns 201 + SavingsGoalResponse
- [ ] GET /accounts/{account_id}/goals — fetches goal with live balance, returns 200 + SavingsGoalResponse or 404 if no goal
- [ ] GET /customers/{customer_id}/goals — fetches all goals for customer, returns 200 + array of SavingsGoalResponse
- [ ] PUT /accounts/{account_id}/goals/{goal_id} — updates goal, returns 200 + SavingsGoalResponse
- [ ] DELETE /accounts/{account_id}/goals/{goal_id} — soft deletes, returns 204 No Content

### Validation & Error Handling

- [ ] target_amount > 0 enforced (400 INVALID_TARGET_AMOUNT)
- [ ] target_date ≥ today on CREATE (400 INVALID_TARGET_DATE)
- [ ] goal_name required (400 INVALID_GOAL_NAME)
- [ ] One goal per account enforced (409 GOAL_ALREADY_EXISTS)
- [ ] Customer ownership verified (403 UNAUTHORIZED_ACCOUNT_ACCESS)
- [ ] Account must be ACTIVE (404 ACCOUNT_NOT_FOUND)
- [ ] All errors mapped in axiosClient.js ✅

### Progress & Status Calculation

- [ ] progress_percentage calculated: (account.balance / target_amount) \* 100
- [ ] progress_percentage capped at 100%
- [ ] progress_percentage not stored (derived on every read)
- [ ] Status calculated: NOT_STARTED / IN_PROGRESS / ACHIEVED / OVERDUE
- [ ] Status recalculated on every read (even if stored)
- [ ] time_remaining_days calculated: target_date - TODAY, never negative
- [ ] Account balance pulled live (not cached)

### UI Components

- [ ] Goal creation form with 3 questions
- [ ] Goal card with progress bar, %, deadline
- [ ] Status badges (OVERDUE, ACHIEVED, etc.)
- [ ] Edit form with pre-populated fields
- [ ] Delete confirmation modal
- [ ] Error messages displayed for all 9 error codes
- [ ] Account list shows "Add a goal" for accounts with no goal

### Theme Support

- [ ] Classic theme: Goal components fully styled
- [ ] Neon theme: Goal components fully styled
- [ ] Progress bar colors distinct in both themes
- [ ] Badges visible and readable in both themes
- [ ] Form inputs, buttons, modals themed consistently

### Audit Logging

- [ ] CREATE_SAVINGS_GOAL logged for all goal creations
- [ ] UPDATE_SAVINGS_GOAL logged for all goal updates
- [ ] DELETE_SAVINGS_GOAL logged for all goal deletions
- [ ] actor_id, actor_role, timestamp logged

---

## Manual Testing Completion

Once all scenarios pass and acceptance criteria are met, the feature is ready for QA and production deployment.

**Sign-off**: **********\_\_********** (QA Lead)  
**Date**: ********\_\_\_\_********
