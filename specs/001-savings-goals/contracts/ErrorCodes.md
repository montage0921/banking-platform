# Savings Goal Error Codes

**Version**: 1.0  
**Date**: June 5, 2026  
**Spec**: [spec.md](../spec.md) | **Contract**: [SavingsGoalResponse.md](SavingsGoalResponse.md)


> **On the identifiers in the examples below.** Values such as `account_id: 42` and
> `customer_id: 100` are illustrative fields inside example error payloads - they document
> the *shape* of a response, not test data. They are deliberately **not** migrated to the
> shared seeded personas: rewriting them as persona logins would make these examples harder
> to read without making anything more reproducible. Fixtures used to *run* scenarios live
> in [quickstart.md](../quickstart.md), which does use the shared personas.


---

## Overview

All Savings Goal endpoints emit errors through the existing `GlobalExceptionHandler` with stable error codes. Frontend MUST map these codes in `src/api/axiosClient.js` only — ad-hoc error parsing in components is prohibited.

---

## Error Response Format

**Standard Error Response** (all HTTP error codes):

```json
{
  "code": "ERROR_CODE",
  "message": "Human-readable message",
  "details": {
    "field": "field_name (if applicable)",
    "value": "actual_value (if applicable)"
  },
  "timestamp": "2026-06-05T10:00:00Z"
}
```

---

## Error Codes

### 400 Bad Request

#### INVALID_TARGET_AMOUNT

**Condition**: target_amount ≤ 0 or not a valid decimal

**HTTP Status**: 400

**Example Response**:

```json
{
  "code": "INVALID_TARGET_AMOUNT",
  "message": "Target amount must be greater than $0",
  "details": {
    "field": "target_amount",
    "value": "-100.00"
  },
  "timestamp": "2026-06-05T10:00:00Z"
}
```

**Frontend Mapping**: Show user message: "Target amount must be greater than $0. Please enter a positive amount."

---

#### INVALID_TARGET_DATE

**Condition**: target_date is in the past (CREATE endpoint only), or invalid date format

**HTTP Status**: 400

**Example Response**:

```json
{
  "code": "INVALID_TARGET_DATE",
  "message": "Target date must be in the future",
  "details": {
    "field": "target_date",
    "value": "2026-01-01"
  },
  "timestamp": "2026-06-05T10:00:00Z"
}
```

**Frontend Mapping**: Show user message: "Target date must be in the future. Please select a date that is today or later."

**Note**: PUT (edit) endpoint may allow past dates if customer extends a missed deadline (TBD by business logic).

---

#### INVALID_GOAL_NAME

**Condition**: goal_name is empty, null, or whitespace-only

**HTTP Status**: 400

**Example Response**:

```json
{
  "code": "INVALID_GOAL_NAME",
  "message": "Goal name is required",
  "details": {
    "field": "goal_name",
    "value": ""
  },
  "timestamp": "2026-06-05T10:00:00Z"
}
```

**Frontend Mapping**: Show user message: "What are you saving for? is required. Please select or enter a goal."

---

#### MISSING_REQUIRED_FIELD

**Condition**: Any required field is missing from request (goal_name, target_amount, or target_date)

**HTTP Status**: 400

**Example Response**:

```json
{
  "code": "MISSING_REQUIRED_FIELD",
  "message": "goal_name is required",
  "details": {
    "field": "goal_name"
  },
  "timestamp": "2026-06-05T10:00:00Z"
}
```

**Frontend Mapping**: Show user message: "[Field name] is required. Please complete all fields."

---

### 403 Forbidden

#### UNAUTHORIZED_ACCOUNT_ACCESS

**Condition**: Authenticated customer_id does not match account.customer_id

**HTTP Status**: 403

**Example Response**:

```json
{
  "code": "UNAUTHORIZED_ACCOUNT_ACCESS",
  "message": "You do not have permission to access this account",
  "details": {
    "account_id": 42,
    "authenticated_customer_id": 100,
    "account_customer_id": 200
  },
  "timestamp": "2026-06-05T10:00:00Z"
}
```

**Frontend Mapping**: Show user message: "You do not have permission to access this account."

**Note**: This should rarely occur in production (would indicate stale session or account transfer). Log as potential security event.

---

### 404 Not Found

#### ACCOUNT_NOT_FOUND

**Condition**: Account does not exist, account.deleted_at IS NOT NULL, or account.status ≠ 'ACTIVE'

**HTTP Status**: 404

**Example Response**:

```json
{
  "code": "ACCOUNT_NOT_FOUND",
  "message": "Account not found or is inactive",
  "details": {
    "account_id": 42
  },
  "timestamp": "2026-06-05T10:00:00Z"
}
```

**Frontend Mapping**: Show user message: "This account is not available. Please select an active account."

**Note**: Combining multiple conditions (not found, deleted, inactive) into single code to avoid leaking account existence information.

---

#### GOAL_NOT_FOUND

**Condition**: Goal does not exist or goal.deleted_at IS NOT NULL

**HTTP Status**: 404

**Example Response**:

```json
{
  "code": "GOAL_NOT_FOUND",
  "message": "Goal not found",
  "details": {
    "goal_id": 1
  },
  "timestamp": "2026-06-05T10:00:00Z"
}
```

**Frontend Mapping**: Show user message: "This goal is no longer available. It may have been deleted."

---

### 409 Conflict

#### GOAL_ALREADY_EXISTS

**Condition**: Goal already exists for the account (one goal per account constraint violation)

**HTTP Status**: 409

**Example Response**:

```json
{
  "code": "GOAL_ALREADY_EXISTS",
  "message": "This account already has a goal. Edit or delete it first.",
  "details": {
    "account_id": 42,
    "existing_goal_id": 1
  },
  "timestamp": "2026-06-05T10:00:00Z"
}
```

**Frontend Mapping**: Show user message: "This account already has a goal. You can edit or delete the existing goal."

**Suggested Action**: Offer user option to edit or delete existing goal instead of creating new one.

---

### 500 Internal Server Error

#### INTERNAL_SERVER_ERROR

**Condition**: Unexpected backend error (database connection failure, unknown exception, etc.)

**HTTP Status**: 500

**Example Response**:

```json
{
  "code": "INTERNAL_SERVER_ERROR",
  "message": "An error occurred while processing your request",
  "details": {},
  "timestamp": "2026-06-05T10:00:00Z"
}
```

**Frontend Mapping**: Show user message: "An unexpected error occurred. Please try again or contact support."

**Action**: Log full stack trace server-side; do not expose to frontend.

---

## Error Code Mapping Table

| HTTP | Code                        | Reason                           | User Message                            | Action                    |
| ---- | --------------------------- | -------------------------------- | --------------------------------------- | ------------------------- |
| 400  | INVALID_TARGET_AMOUNT       | target_amount ≤ 0                | "Target amount must be greater than $0" | Show input error on form  |
| 400  | INVALID_TARGET_DATE         | target_date in past              | "Target date must be in the future"     | Show input error on form  |
| 400  | INVALID_GOAL_NAME           | goal_name empty                  | "Goal name is required"                 | Show input error on form  |
| 400  | MISSING_REQUIRED_FIELD      | Required field missing           | "[Field name] is required"              | Show input error on form  |
| 403  | UNAUTHORIZED_ACCOUNT_ACCESS | Customer doesn't own account     | "You don't have permission"             | Redirect to account list  |
| 404  | ACCOUNT_NOT_FOUND           | Account missing/deleted/inactive | "Account not available"                 | Redirect to account list  |
| 404  | GOAL_NOT_FOUND              | Goal missing/deleted             | "Goal not found"                        | Redirect to account list  |
| 409  | GOAL_ALREADY_EXISTS         | Goal exists for account          | "Account already has goal"              | Offer edit/delete options |
| 500  | INTERNAL_SERVER_ERROR       | Unexpected error                 | "Unexpected error. Try again"           | Retry or contact support  |

---

## Frontend Integration

### In `src/api/axiosClient.js`

```javascript
const goalErrorMessages = {
  INVALID_TARGET_AMOUNT: "Target amount must be greater than $0",
  INVALID_TARGET_DATE: "Target date must be in the future",
  INVALID_GOAL_NAME: "Goal name is required",
  MISSING_REQUIRED_FIELD: (details) => `${details.field} is required`,
  UNAUTHORIZED_ACCOUNT_ACCESS:
    "You do not have permission to access this account",
  ACCOUNT_NOT_FOUND: "Account not found or is inactive",
  GOAL_NOT_FOUND: "Goal not found",
  GOAL_ALREADY_EXISTS:
    "This account already has a goal. Edit or delete it first.",
  INTERNAL_SERVER_ERROR: "An unexpected error occurred. Please try again.",
};

// Use this mapping in error handler
if (response.data.code in goalErrorMessages) {
  showUserMessage(goalErrorMessages[response.data.code]);
}
```

### In Component/Hook Error Handling

```javascript
// DON'T do this (ad-hoc parsing):
if (error.response.data.message.includes("Target amount")) {
  showUserMessage("...");
}

// DO use the axiosClient mapping:
import { getErrorMessage } from "src/api/axiosClient";
showUserMessage(getErrorMessage(error));
```

---

## Error Scenarios by Endpoint

### POST /accounts/{account_id}/goals

**Possible Errors**:

- 400 INVALID_TARGET_AMOUNT
- 400 INVALID_TARGET_DATE
- 400 INVALID_GOAL_NAME
- 400 MISSING_REQUIRED_FIELD
- 403 UNAUTHORIZED_ACCOUNT_ACCESS
- 404 ACCOUNT_NOT_FOUND
- 409 GOAL_ALREADY_EXISTS
- 500 INTERNAL_SERVER_ERROR

---

### GET /accounts/{account_id}/goals

**Possible Errors**:

- 403 UNAUTHORIZED_ACCOUNT_ACCESS
- 404 ACCOUNT_NOT_FOUND
- 404 GOAL_NOT_FOUND (no goal exists for account)
- 500 INTERNAL_SERVER_ERROR

---

### PUT /accounts/{account_id}/goals/{goal_id}

**Possible Errors**:

- 400 INVALID_TARGET_AMOUNT
- 400 INVALID_TARGET_DATE
- 400 INVALID_GOAL_NAME
- 400 MISSING_REQUIRED_FIELD
- 403 UNAUTHORIZED_ACCOUNT_ACCESS
- 404 ACCOUNT_NOT_FOUND
- 404 GOAL_NOT_FOUND
- 500 INTERNAL_SERVER_ERROR

---

### DELETE /accounts/{account_id}/goals/{goal_id}

**Possible Errors**:

- 403 UNAUTHORIZED_ACCOUNT_ACCESS
- 404 ACCOUNT_NOT_FOUND
- 404 GOAL_NOT_FOUND
- 500 INTERNAL_SERVER_ERROR

---

### GET /customers/{customer_id}/goals

**Possible Errors**:

- 403 UNAUTHORIZED_ACCOUNT_ACCESS (if customer_id in URL doesn't match authenticated customer)
- 500 INTERNAL_SERVER_ERROR

---

## Logging & Monitoring

### Backend

Log all errors with:

- error code
- HTTP status
- timestamp
- user/customer ID (for tracing)
- endpoint and method
- request data (sanitized, no sensitive info)

**Example**:

```
[ERROR] 2026-06-05 10:00:00 | INVALID_TARGET_AMOUNT | POST /accounts/42/goals | customer_id=100 | target_amount=-100.00
```

### Frontend

Log all errors to frontend analytics with:

- error code
- endpoint
- user action that triggered error
- timestamp

**Example**:

```
logEvent('goal_creation_error', {
  code: 'INVALID_TARGET_AMOUNT',
  endpoint: 'POST /accounts/42/goals'
});
```

---

## Version History

### v1.0 (June 5, 2026)

- Initial error code set (9 unique codes)
- 5 HTTP status codes (400, 403, 404, 409, 500)
- Frontend mapping guidelines
- Endpoint error scenarios documented
