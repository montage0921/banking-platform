# Agent Confirmation Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the Savings Insight chatbot agent the ability to propose a money transfer between a customer's own accounts, and only execute it after a separate, explicit, authenticated confirmation call - so the agent can never autonomously move money (AC4), with every step of the flow audited (AC5/CFG-03).

**Architecture:** A reusable `ConfirmationGateService` persists a `PendingAgentAction` (token, customer, action type, parameters, expiry) when the agent proposes something, and only a dedicated `POST /api/chat/confirmations/{token}` endpoint - outside the model's tool-calling loop entirely - can execute it. `TRANSFER` is the first and only action type wired up now, reusing the existing `MonetaryOperationService.transfer` for real execution. The chat response and the frontend widget are extended to carry and render the pending proposal as a structured Confirm/Dismiss card rather than free text.

**Tech Stack:** Spring Boot, Spring Data JPA (primary H2/MySQL datasource), Spring AI tool-calling (`@Tool`/`ToolContext`), JUnit 5 + Mockito + AssertJ, `@DataJpaTest`/`@WebMvcTest`, React + `@tanstack/react-query` + axios, Vitest/RTL (matching `ChatWidget.test.jsx`).

**Spec:** `specs/002-agent-confirmation-gate/spec.md`

## Global Constraints

- All new/modified files under `backend/` use CRLF line endings, matching the rest of this repository's checkout - if editing with a tool that writes LF, convert before saving (e.g. `sed -i 's/$/\r/'` on a fresh LF file).
- New entities/DTOs in this feature use plain Java (explicit getters/setters, public constructors) - no Lombok - matching `StandingOrderEntity` and `ChatQueryResponse`, the two closest existing precedents.
- JSON annotations (`@JsonProperty`) come from `com.fasterxml.jackson.annotation`. The JSON mapper bean/type is `tools.jackson.databind.json.JsonMapper` (Jackson 3 databind namespace, per `MonetaryOperationService`) - do not import `com.fasterxml.jackson.databind.ObjectMapper`.
- New JPA entities go on the primary datasource (default `JpaRepository`, no datasource `@Qualifier`) - this is customer/account/transfer domain data, not chatbot/RAG data, so it does NOT use `ChatInteractionLogRepository`'s separate `chatbotVectorJdbcTemplate` Postgres datasource.
- New backend exceptions follow the existing `ApiException` subclass-per-status-code pattern (see `NotFoundException`, `ConflictException`) with a matching `@ExceptionHandler` added to `GlobalExceptionHandler`, returning `com.group1.banking.dto.common.ErrorResponse`.
- `PendingAgentActionStatus.DENIED` is defined per the spec's Key Entities section but is not set by any code path in this plan (no "customer explicitly dismisses" endpoint exists yet) - this is intentional, not a gap; a future feature can set it.
- A `PendingAgentAction` whose confirm attempt fails *after* being marked `EXECUTED` (e.g. the underlying `MonetaryOperationService.transfer` call itself fails, such as a balance that changed since proposing) is left in status `EXECUTED` - its job as a single-use token is done, and re-deriving a more precise status is out of scope (see spec Edge Cases). The failure itself is still returned to the caller from the real `transfer` call's own error response.

---

## File Structure

New backend files:
- `backend/src/main/java/com/group1/banking/entity/PendingAgentActionStatus.java` - status enum
- `backend/src/main/java/com/group1/banking/entity/PendingAgentActionType.java` - action type enum
- `backend/src/main/java/com/group1/banking/entity/PendingAgentActionEntity.java` - the JPA entity
- `backend/src/main/java/com/group1/banking/repository/PendingAgentActionRepository.java` - `JpaRepository<PendingAgentActionEntity, String>`
- `backend/src/main/java/com/group1/banking/exception/GoneException.java` - new 410 exception type
- `backend/src/main/java/com/group1/banking/service/ConfirmationGateService.java` - the reusable propose/confirm gate (lives alongside `AuditService`, the other shared/generic capability)
- `backend/src/main/java/com/group1/banking/service/impl/TransferChatTool.java` - the one `@Tool` the model can call to propose a transfer
- `backend/src/main/java/com/group1/banking/service/impl/PendingActionTracker.java` - per-turn tracker (same pattern as `SavingsChatCitationTracker`)
- `backend/src/main/java/com/group1/banking/dto/chat/PendingConfirmationView.java` - the `pending_confirmation` response DTO
- `backend/src/main/java/com/group1/banking/controller/AgentActionConfirmationController.java` - `POST /api/chat/confirmations/{token}`

Modified backend files:
- `backend/src/main/java/com/group1/banking/exception/GlobalExceptionHandler.java` - register `GoneException` handler
- `backend/src/main/java/com/group1/banking/dto/chat/ChatQueryResponse.java` - add `pendingConfirmation` field
- `backend/src/main/java/com/group1/banking/service/impl/SavingsInsightChatService.java` - wire in `PendingActionTracker`, pass `actorRole` through `ToolContext`
- `backend/src/main/java/com/group1/banking/config/ChatbotAiConfig.java` - register `TransferChatTool` as a tool, extend the system prompt

New/modified frontend files:
- `src/api/chat.js` - add `confirmAgentAction(token)`
- `src/hooks/useSavingsChat.js` - add `useConfirmAgentAction()`
- `src/components/ChatWidget.jsx` - render the pending-confirmation card and wire the Confirm/Dismiss actions
- `src/test/components/ChatWidget.test.jsx` - cover the new card

---

## Task 1: `PendingAgentAction` entity, status/type enums, repository

**Files:**
- Create: `backend/src/main/java/com/group1/banking/entity/PendingAgentActionStatus.java`
- Create: `backend/src/main/java/com/group1/banking/entity/PendingAgentActionType.java`
- Create: `backend/src/main/java/com/group1/banking/entity/PendingAgentActionEntity.java`
- Create: `backend/src/main/java/com/group1/banking/repository/PendingAgentActionRepository.java`
- Test: `backend/src/test/java/com/group1/banking/repository/PendingAgentActionRepositoryTest.java`

**Interfaces:**
- Produces: `PendingAgentActionEntity` with fields `token` (String, `@Id`), `customerId` (Long), `actionType` (`PendingAgentActionType`), `parametersJson` (String), `humanSummary` (String), `status` (`PendingAgentActionStatus`), `createdAt`/`expiresAt` (`LocalDateTime`), plus getters/setters and a `@PrePersist` that stamps `createdAt`. `PendingAgentActionRepository extends JpaRepository<PendingAgentActionEntity, String>` - `findById(token)` is all Task 2 needs.

- [ ] **Step 1: Write the failing repository test**

```java
package com.group1.banking.repository;

import com.group1.banking.entity.PendingAgentActionEntity;
import com.group1.banking.entity.PendingAgentActionStatus;
import com.group1.banking.entity.PendingAgentActionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class PendingAgentActionRepositoryTest {

    @Autowired
    private PendingAgentActionRepository repository;

    private PendingAgentActionEntity build(String token) {
        PendingAgentActionEntity entity = new PendingAgentActionEntity();
        entity.setToken(token);
        entity.setCustomerId(42L);
        entity.setActionType(PendingAgentActionType.TRANSFER);
        entity.setParametersJson("{\"fromAccountId\":1,\"toAccountId\":2,\"amount\":50.00}");
        entity.setHumanSummary("Transfer $50.00 from Checking to Savings.");
        entity.setStatus(PendingAgentActionStatus.PENDING);
        entity.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        return entity;
    }

    @Test
    void save_shouldPersistAndSetCreatedAt() {
        PendingAgentActionEntity saved = repository.save(build("token-001"));
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void findById_shouldReturnEntity_whenTokenExists() {
        repository.save(build("token-002"));

        Optional<PendingAgentActionEntity> found = repository.findById("token-002");

        assertThat(found).isPresent();
        assertThat(found.get().getCustomerId()).isEqualTo(42L);
        assertThat(found.get().getActionType()).isEqualTo(PendingAgentActionType.TRANSFER);
        assertThat(found.get().getStatus()).isEqualTo(PendingAgentActionStatus.PENDING);
    }

    @Test
    void findById_shouldReturnEmpty_whenTokenDoesNotExist() {
        Optional<PendingAgentActionEntity> found = repository.findById("no-such-token");
        assertThat(found).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw.cmd test -Dtest=PendingAgentActionRepositoryTest`
Expected: FAIL to compile - `PendingAgentActionEntity`, `PendingAgentActionStatus`, `PendingAgentActionType`, `PendingAgentActionRepository` don't exist yet.

- [ ] **Step 3: Create the status and type enums**

```java
package com.group1.banking.entity;

public enum PendingAgentActionStatus {
    PENDING,
    EXECUTED,
    EXPIRED,
    DENIED
}
```

```java
package com.group1.banking.entity;

public enum PendingAgentActionType {
    TRANSFER
}
```

- [ ] **Step 4: Create the entity**

```java
package com.group1.banking.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * A financial or account-changing action the chat agent proposed but has not
 * executed - AC4's "confirm before completing" gate. Persisted on the primary
 * datasource (not the chatbot's separate Postgres/pgvector store) since this is
 * account/transfer domain data. See ConfirmationGateService.
 */
@Entity
@Table(name = "pending_agent_actions", indexes = {
        @Index(name = "idx_paa_customer_id", columnList = "customer_id"),
        @Index(name = "idx_paa_status", columnList = "status")
})
public class PendingAgentActionEntity {

    @Id
    @Column(name = "token", length = 36)
    private String token;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 20)
    private PendingAgentActionType actionType;

    @Column(name = "parameters_json", nullable = false, columnDefinition = "TEXT")
    private String parametersJson;

    @Column(name = "human_summary", nullable = false, columnDefinition = "TEXT")
    private String humanSummary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PendingAgentActionStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public PendingAgentActionType getActionType() { return actionType; }
    public void setActionType(PendingAgentActionType actionType) { this.actionType = actionType; }
    public String getParametersJson() { return parametersJson; }
    public void setParametersJson(String parametersJson) { this.parametersJson = parametersJson; }
    public String getHumanSummary() { return humanSummary; }
    public void setHumanSummary(String humanSummary) { this.humanSummary = humanSummary; }
    public PendingAgentActionStatus getStatus() { return status; }
    public void setStatus(PendingAgentActionStatus status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
}
```

- [ ] **Step 5: Create the repository**

```java
package com.group1.banking.repository;

import com.group1.banking.entity.PendingAgentActionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PendingAgentActionRepository extends JpaRepository<PendingAgentActionEntity, String> {
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `cd backend && ./mvnw.cmd test -Dtest=PendingAgentActionRepositoryTest`
Expected: PASS (3/3 tests)

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/group1/banking/entity/PendingAgentActionStatus.java \
        backend/src/main/java/com/group1/banking/entity/PendingAgentActionType.java \
        backend/src/main/java/com/group1/banking/entity/PendingAgentActionEntity.java \
        backend/src/main/java/com/group1/banking/repository/PendingAgentActionRepository.java \
        backend/src/test/java/com/group1/banking/repository/PendingAgentActionRepositoryTest.java
git commit -m "feat: add PendingAgentAction entity, status/type enums, repository"
```

---

## Task 2: `GoneException` (410) + `GlobalExceptionHandler` registration

**Files:**
- Create: `backend/src/main/java/com/group1/banking/exception/GoneException.java`
- Modify: `backend/src/main/java/com/group1/banking/exception/GlobalExceptionHandler.java`
- Test: `backend/src/test/java/com/group1/banking/exception/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Produces: `GoneException(String code, String message, Object details)`, an `ApiException` subclass with status 410, handled by `GlobalExceptionHandler` the same way `NotFoundException`/`ConflictException` already are.

- [ ] **Step 1: Write the failing test**

```java
package com.group1.banking.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void handleGone_shouldReturn410WithCodeAndMessage() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        GoneException ex = new GoneException("CONFIRMATION_EXPIRED", "This confirmation has expired.", null);

        ResponseEntity<com.group1.banking.dto.common.ErrorResponse> response = handler.handleGone(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(410);
        assertThat(response.getBody().getCode()).isEqualTo("CONFIRMATION_EXPIRED");
        assertThat(response.getBody().getMessage()).isEqualTo("This confirmation has expired.");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw.cmd test -Dtest=GlobalExceptionHandlerTest`
Expected: FAIL to compile - `GoneException` and `handleGone` don't exist yet.

- [ ] **Step 3: Create `GoneException`**

```java
package com.group1.banking.exception;

public class GoneException extends ApiException {
    public GoneException(String code, String message, Object details) {
        super(410, code, message, details);
    }
}
```

- [ ] **Step 4: Add the handler to `GlobalExceptionHandler`**

Add this method next to `handleNotFound` (around line 86 of `backend/src/main/java/com/group1/banking/exception/GlobalExceptionHandler.java`):

```java
    @ExceptionHandler(GoneException.class)
    public ResponseEntity<ErrorResponse> handleGone(GoneException ex) {
        ApiException apiEx = ex;
        logger.warn("Confirmation gone/expired. code={}, message={}", apiEx.getCode(), apiEx.getMessage());
        return ResponseEntity.status(410)
                .body(new ErrorResponse(apiEx.getCode(), apiEx.getMessage(), apiEx.getDetails()));
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && ./mvnw.cmd test -Dtest=GlobalExceptionHandlerTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/group1/banking/exception/GoneException.java \
        backend/src/main/java/com/group1/banking/exception/GlobalExceptionHandler.java \
        backend/src/test/java/com/group1/banking/exception/GlobalExceptionHandlerTest.java
git commit -m "feat: add GoneException (410) for expired agent confirmations"
```

---

## Task 3: `ConfirmationGateService` (propose / confirmAndConsume)

**Files:**
- Create: `backend/src/main/java/com/group1/banking/service/ConfirmationGateService.java`
- Test: `backend/src/test/java/com/group1/banking/service/ConfirmationGateServiceTest.java`

**Interfaces:**
- Consumes: `PendingAgentActionRepository` (Task 1), `AuditService.log(String actorId, String actorRole, String action, String resourceType, String resourceId, String outcome)` (existing), `tools.jackson.databind.json.JsonMapper` (existing bean).
- Produces: `PendingAgentActionEntity propose(Long customerId, String actorRole, PendingAgentActionType actionType, Object parameters, String humanSummary)` and `PendingAgentActionEntity confirmAndConsume(String token, Long customerId, String actorRole)`, throwing `NotFoundException` (token missing or not owned by `customerId`), `ConflictException` (already resolved), or `GoneException` (expired) - all from `com.group1.banking.exception`. Later tasks call these two methods and nothing else on this class.

- [ ] **Step 1: Write the failing tests**

```java
package com.group1.banking.service;

import com.group1.banking.entity.PendingAgentActionEntity;
import com.group1.banking.entity.PendingAgentActionStatus;
import com.group1.banking.entity.PendingAgentActionType;
import com.group1.banking.exception.ConflictException;
import com.group1.banking.exception.GoneException;
import com.group1.banking.exception.NotFoundException;
import com.group1.banking.repository.PendingAgentActionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfirmationGateServiceTest {

    private static final Long CUSTOMER_ID = 42L;
    private static final String ACTOR_ROLE = "CUSTOMER";

    private PendingAgentActionRepository repository;
    private AuditService auditService;
    private ConfirmationGateService service;

    @BeforeEach
    void setUp() {
        repository = mock(PendingAgentActionRepository.class);
        auditService = mock(AuditService.class);
        JsonMapper objectMapper = new JsonMapper();
        service = new ConfirmationGateService(repository, auditService, objectMapper);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void propose_shouldPersistPendingRowAndAudit() {
        PendingAgentActionEntity result = service.propose(CUSTOMER_ID, ACTOR_ROLE,
                PendingAgentActionType.TRANSFER, Map.of("fromAccountId", 1, "toAccountId", 2), "Transfer $50.00");

        assertThat(result.getCustomerId()).isEqualTo(CUSTOMER_ID);
        assertThat(result.getActionType()).isEqualTo(PendingAgentActionType.TRANSFER);
        assertThat(result.getStatus()).isEqualTo(PendingAgentActionStatus.PENDING);
        assertThat(result.getToken()).isNotBlank();
        assertThat(result.getExpiresAt()).isAfter(LocalDateTime.now());
        assertThat(result.getParametersJson()).contains("fromAccountId");

        verify(auditService).log(eq("42"), eq(ACTOR_ROLE), eq("AGENT_ACTION_PROPOSED"),
                eq("PENDING_AGENT_ACTION"), eq(result.getToken()), eq("PROPOSED"));
    }

    @Test
    void confirmAndConsume_shouldExecuteAndAudit_whenValidAndOwned() {
        PendingAgentActionEntity pending = pendingEntity("tok-1", PendingAgentActionStatus.PENDING,
                LocalDateTime.now().plusMinutes(5));
        when(repository.findById("tok-1")).thenReturn(Optional.of(pending));

        PendingAgentActionEntity result = service.confirmAndConsume("tok-1", CUSTOMER_ID, ACTOR_ROLE);

        assertThat(result.getStatus()).isEqualTo(PendingAgentActionStatus.EXECUTED);
        verify(auditService).log(eq("42"), eq(ACTOR_ROLE), eq("AGENT_ACTION_CONFIRMED"),
                eq("PENDING_AGENT_ACTION"), eq("tok-1"), eq("CONFIRMED"));
    }

    @Test
    void confirmAndConsume_shouldThrowNotFound_whenTokenMissing() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmAndConsume("missing", CUSTOMER_ID, ACTOR_ROLE))
                .isInstanceOf(NotFoundException.class);
        verify(auditService).log(eq("42"), eq(ACTOR_ROLE), eq("AGENT_ACTION_DENIED"),
                eq("PENDING_AGENT_ACTION"), eq("missing"), eq("NOT_FOUND"));
    }

    @Test
    void confirmAndConsume_shouldThrowNotFound_whenTokenBelongsToDifferentCustomer() {
        PendingAgentActionEntity pending = pendingEntity("tok-2", PendingAgentActionStatus.PENDING,
                LocalDateTime.now().plusMinutes(5));
        pending.setCustomerId(999L);
        when(repository.findById("tok-2")).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.confirmAndConsume("tok-2", CUSTOMER_ID, ACTOR_ROLE))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void confirmAndConsume_shouldThrowConflict_whenAlreadyExecuted() {
        PendingAgentActionEntity pending = pendingEntity("tok-3", PendingAgentActionStatus.EXECUTED,
                LocalDateTime.now().plusMinutes(5));
        when(repository.findById("tok-3")).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.confirmAndConsume("tok-3", CUSTOMER_ID, ACTOR_ROLE))
                .isInstanceOf(ConflictException.class);
        verify(auditService).log(eq("42"), eq(ACTOR_ROLE), eq("AGENT_ACTION_DENIED"),
                eq("PENDING_AGENT_ACTION"), eq("tok-3"), eq("ALREADY_RESOLVED"));
    }

    @Test
    void confirmAndConsume_shouldThrowGoneAndMarkExpired_whenPastExpiry() {
        PendingAgentActionEntity pending = pendingEntity("tok-4", PendingAgentActionStatus.PENDING,
                LocalDateTime.now().minusMinutes(1));
        when(repository.findById("tok-4")).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.confirmAndConsume("tok-4", CUSTOMER_ID, ACTOR_ROLE))
                .isInstanceOf(GoneException.class);
        assertThat(pending.getStatus()).isEqualTo(PendingAgentActionStatus.EXPIRED);
        verify(auditService).log(eq("42"), eq(ACTOR_ROLE), eq("AGENT_ACTION_DENIED"),
                eq("PENDING_AGENT_ACTION"), eq("tok-4"), eq("EXPIRED"));
    }

    @Test
    void confirmAndConsume_doesNotFailWhenAuditLoggingThrows() {
        PendingAgentActionEntity pending = pendingEntity("tok-5", PendingAgentActionStatus.PENDING,
                LocalDateTime.now().plusMinutes(5));
        when(repository.findById("tok-5")).thenReturn(Optional.of(pending));
        org.mockito.Mockito.doThrow(new RuntimeException("audit down"))
                .when(auditService).log(any(), any(), any(), any(), any(), any());

        PendingAgentActionEntity result = service.confirmAndConsume("tok-5", CUSTOMER_ID, ACTOR_ROLE);

        assertThat(result.getStatus()).isEqualTo(PendingAgentActionStatus.EXECUTED);
    }

    private PendingAgentActionEntity pendingEntity(String token, PendingAgentActionStatus status, LocalDateTime expiresAt) {
        PendingAgentActionEntity entity = new PendingAgentActionEntity();
        entity.setToken(token);
        entity.setCustomerId(CUSTOMER_ID);
        entity.setActionType(PendingAgentActionType.TRANSFER);
        entity.setParametersJson("{}");
        entity.setHumanSummary("Transfer $50.00");
        entity.setStatus(status);
        entity.setExpiresAt(expiresAt);
        return entity;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw.cmd test -Dtest=ConfirmationGateServiceTest`
Expected: FAIL to compile - `ConfirmationGateService` doesn't exist yet.

- [ ] **Step 3: Write the implementation**

```java
package com.group1.banking.service;

import com.group1.banking.entity.PendingAgentActionEntity;
import com.group1.banking.entity.PendingAgentActionStatus;
import com.group1.banking.entity.PendingAgentActionType;
import com.group1.banking.exception.ConflictException;
import com.group1.banking.exception.GoneException;
import com.group1.banking.exception.NotFoundException;
import com.group1.banking.repository.PendingAgentActionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * The reusable propose/confirm gate behind AC4: no mutating chat tool executes its
 * action directly. Instead it calls {@link #propose} here, which persists a pending
 * row and returns a token; only a separate, explicitly-called confirm endpoint may
 * later call {@link #confirmAndConsume} to allow the real action to proceed. TRANSFER
 * is the first user of this; any future mutating tool (chat-native or MCP-sourced)
 * can reuse it without duplicating this logic.
 *
 * Every proposal and every confirmation resolution (success or denial) is recorded
 * through the shared AuditService (CFG-03) - an audit failure is logged but never
 * allowed to block the proposal or the confirmation, consistent with how
 * SavingsInsightChatService already treats audit failures.
 */
@Service
public class ConfirmationGateService {

    private static final Logger log = LoggerFactory.getLogger(ConfirmationGateService.class);
    private static final long TTL_MINUTES = 5;
    private static final String RESOURCE_TYPE = "PENDING_AGENT_ACTION";
    private static final String ACTION_PROPOSED = "AGENT_ACTION_PROPOSED";
    private static final String ACTION_CONFIRMED = "AGENT_ACTION_CONFIRMED";
    private static final String ACTION_DENIED = "AGENT_ACTION_DENIED";

    private final PendingAgentActionRepository repository;
    private final AuditService auditService;
    private final JsonMapper objectMapper;

    public ConfirmationGateService(PendingAgentActionRepository repository, AuditService auditService,
                                    JsonMapper objectMapper) {
        this.repository = repository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    public PendingAgentActionEntity propose(Long customerId, String actorRole, PendingAgentActionType actionType,
                                             Object parameters, String humanSummary) {
        PendingAgentActionEntity entity = new PendingAgentActionEntity();
        entity.setToken(UUID.randomUUID().toString());
        entity.setCustomerId(customerId);
        entity.setActionType(actionType);
        entity.setParametersJson(objectMapper.writeValueAsString(parameters));
        entity.setHumanSummary(humanSummary);
        entity.setStatus(PendingAgentActionStatus.PENDING);
        entity.setExpiresAt(LocalDateTime.now().plusMinutes(TTL_MINUTES));

        PendingAgentActionEntity saved = repository.save(entity);
        audit(customerId, actorRole, ACTION_PROPOSED, saved.getToken(), "PROPOSED");
        return saved;
    }

    @Transactional
    public PendingAgentActionEntity confirmAndConsume(String token, Long customerId, String actorRole) {
        Optional<PendingAgentActionEntity> found = repository.findById(token)
                .filter(entity -> entity.getCustomerId().equals(customerId));

        if (found.isEmpty()) {
            audit(customerId, actorRole, ACTION_DENIED, token, "NOT_FOUND");
            throw new NotFoundException("CONFIRMATION_NOT_FOUND", "No pending action found for this token.", null);
        }

        PendingAgentActionEntity entity = found.get();

        if (entity.getStatus() != PendingAgentActionStatus.PENDING) {
            audit(customerId, actorRole, ACTION_DENIED, token, "ALREADY_RESOLVED");
            throw new ConflictException("CONFIRMATION_ALREADY_RESOLVED",
                    "This action has already been " + entity.getStatus().name().toLowerCase() + ".", null);
        }

        if (entity.getExpiresAt().isBefore(LocalDateTime.now())) {
            entity.setStatus(PendingAgentActionStatus.EXPIRED);
            repository.save(entity);
            audit(customerId, actorRole, ACTION_DENIED, token, "EXPIRED");
            throw new GoneException("CONFIRMATION_EXPIRED", "This confirmation has expired. Please ask again.", null);
        }

        entity.setStatus(PendingAgentActionStatus.EXECUTED);
        PendingAgentActionEntity saved = repository.save(entity);
        audit(customerId, actorRole, ACTION_CONFIRMED, token, "CONFIRMED");
        return saved;
    }

    private void audit(Long customerId, String actorRole, String action, String token, String outcome) {
        try {
            auditService.log(customerId.toString(), actorRole, action, RESOURCE_TYPE, token, outcome);
        } catch (Exception ex) {
            log.error("Failed to write audit log entry for agent action token {}", token, ex);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw.cmd test -Dtest=ConfirmationGateServiceTest`
Expected: PASS (7/7 tests)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/group1/banking/service/ConfirmationGateService.java \
        backend/src/test/java/com/group1/banking/service/ConfirmationGateServiceTest.java
git commit -m "feat: add ConfirmationGateService (propose/confirm gate for AC4)"
```

---

## Task 4: `TransferChatTool` (the one mutating tool the agent can call)

**Files:**
- Create: `backend/src/main/java/com/group1/banking/service/impl/TransferChatTool.java`
- Test: `backend/src/test/java/com/group1/banking/service/impl/TransferChatToolTest.java`

**Interfaces:**
- Consumes: `SavingsChatContextService.getAccountSummaries(Long customerId)` returning `List<AccountSummary>` (existing, `AccountSummary(Long accountId, String accountType, String status, BigDecimal balance)`), `ConfirmationGateService.propose(...)` (Task 3), `PendingActionTracker.recordProposal(String token, String actionType, String summary, LocalDateTime expiresAt)` (Task 5 - stub it as a plain no-arg-constructible class before Task 5 lands, or implement Task 5 first; this task assumes it already exists).
- Produces: `@Tool public String proposeTransfer(Long fromAccountId, Long toAccountId, BigDecimal amount, String description, ToolContext toolContext)` on a `@Component`-annotated class, registered as a Spring AI tool the same way `SavingsChatTools` is (wired into `ChatbotAiConfig` in Task 6).

**Note:** implement Task 5 (`PendingActionTracker`) before this task, since `TransferChatTool` depends on it - the task order above is FIFO by file responsibility, not strict execution order; run Task 5 first if working through this plan top to bottom causes a compile error here.

- [ ] **Step 1: Write the failing test**

```java
package com.group1.banking.service.impl;

import com.group1.banking.dto.chat.AccountSummary;
import com.group1.banking.entity.PendingAgentActionEntity;
import com.group1.banking.entity.PendingAgentActionStatus;
import com.group1.banking.entity.PendingAgentActionType;
import com.group1.banking.service.ConfirmationGateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransferChatToolTest {

    private static final Long CUSTOMER_ID = 42L;

    private SavingsChatContextService contextService;
    private ConfirmationGateService confirmationGateService;
    private PendingActionTracker pendingActionTracker;
    private TransferChatTool tool;

    @BeforeEach
    void setUp() {
        contextService = mock(SavingsChatContextService.class);
        confirmationGateService = mock(ConfirmationGateService.class);
        pendingActionTracker = new PendingActionTracker();
        tool = new TransferChatTool(contextService, confirmationGateService, pendingActionTracker);

        when(contextService.getAccountSummaries(CUSTOMER_ID)).thenReturn(List.of(
                new AccountSummary(1L, "CHECKING", "ACTIVE", new BigDecimal("300.00")),
                new AccountSummary(2L, "SAVINGS", "ACTIVE", new BigDecimal("50.00"))
        ));
    }

    private ToolContext toolContext(String actorRole) {
        return new ToolContext(Map.of("customerId", CUSTOMER_ID, "actorRole", actorRole));
    }

    @Test
    void proposeTransfer_shouldCreateProposal_andRecordItOnTracker() {
        PendingAgentActionEntity proposed = proposedEntity();
        when(confirmationGateService.propose(eq(CUSTOMER_ID), eq("CUSTOMER"), eq(PendingAgentActionType.TRANSFER),
                any(), any())).thenReturn(proposed);

        String reply = tool.proposeTransfer(1L, 2L, new BigDecimal("50.00"), "rent", toolContext("CUSTOMER"));

        assertThat(reply).contains("50.00").contains(proposed.getToken());
        assertThat(pendingActionTracker.drainProposal()).isPresent();
        assertThat(pendingActionTracker.drainProposal()).isEmpty();
    }

    @Test
    void proposeTransfer_shouldRefuse_whenAccountNotOwnedByCustomer() {
        String reply = tool.proposeTransfer(1L, 999L, new BigDecimal("50.00"), null, toolContext("CUSTOMER"));

        assertThat(reply).containsIgnoringCase("aren't available");
        assertThat(pendingActionTracker.drainProposal()).isEmpty();
    }

    @Test
    void proposeTransfer_shouldRefuse_whenAmountExceedsBalance() {
        String reply = tool.proposeTransfer(2L, 1L, new BigDecimal("999.00"), null, toolContext("CUSTOMER"));

        assertThat(reply).containsIgnoringCase("balance");
        assertThat(pendingActionTracker.drainProposal()).isEmpty();
    }

    @Test
    void proposeTransfer_shouldRefuse_whenSameAccountBothSides() {
        String reply = tool.proposeTransfer(1L, 1L, new BigDecimal("10.00"), null, toolContext("CUSTOMER"));

        assertThat(reply).containsIgnoringCase("same account");
    }

    @Test
    void proposeTransfer_shouldRefuse_whenAmountNotPositive() {
        String reply = tool.proposeTransfer(1L, 2L, new BigDecimal("0.00"), null, toolContext("CUSTOMER"));

        assertThat(reply).containsIgnoringCase("at least");
    }

    private PendingAgentActionEntity proposedEntity() {
        PendingAgentActionEntity entity = new PendingAgentActionEntity();
        entity.setToken("tok-abc");
        entity.setCustomerId(CUSTOMER_ID);
        entity.setActionType(PendingAgentActionType.TRANSFER);
        entity.setStatus(PendingAgentActionStatus.PENDING);
        entity.setHumanSummary("Transfer $50.00 from your CHECKING account (#1) to your SAVINGS account (#2).");
        entity.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        return entity;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw.cmd test -Dtest=TransferChatToolTest`
Expected: FAIL to compile - `TransferChatTool` doesn't exist yet.

- [ ] **Step 3: Write the implementation**

```java
package com.group1.banking.service.impl;

import com.group1.banking.dto.chat.AccountSummary;
import com.group1.banking.dto.customer.TransferRequest;
import com.group1.banking.entity.PendingAgentActionEntity;
import com.group1.banking.entity.PendingAgentActionType;
import com.group1.banking.service.ConfirmationGateService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * The only mutating tool the Savings Insight chatbot agent can call. It never moves
 * money itself - it validates the request and hands off to {@link ConfirmationGateService}
 * to create a pending proposal, which only the separate
 * {@code POST /api/chat/confirmations/{token}} endpoint can later execute. This is
 * what makes AC4 ("never autonomously completes a financial action") a structural
 * property rather than a prompted behaviour.
 */
@Component
public class TransferChatTool {

    private static final String CUSTOMER_ID_KEY = "customerId";
    private static final String ACTOR_ROLE_KEY = "actorRole";
    private static final BigDecimal MIN_AMOUNT = new BigDecimal("0.01");

    private final SavingsChatContextService contextService;
    private final ConfirmationGateService confirmationGateService;
    private final PendingActionTracker pendingActionTracker;

    public TransferChatTool(SavingsChatContextService contextService,
                             ConfirmationGateService confirmationGateService,
                             PendingActionTracker pendingActionTracker) {
        this.contextService = contextService;
        this.confirmationGateService = confirmationGateService;
        this.pendingActionTracker = pendingActionTracker;
    }

    @Tool(description = "Propose a transfer of money between two of the customer's own accounts. "
            + "This does NOT move any money - it only prepares the transfer for the customer to "
            + "explicitly confirm in the chat window. After calling this, tell the customer the "
            + "transfer is awaiting their confirmation - never say it is complete or that the money "
            + "has moved.")
    public String proposeTransfer(
            @ToolParam(description = "The account ID to transfer money FROM") Long fromAccountId,
            @ToolParam(description = "The account ID to transfer money TO") Long toAccountId,
            @ToolParam(description = "The amount to transfer, e.g. 200.00") BigDecimal amount,
            @ToolParam(description = "A short description/memo for the transfer", required = false) String description,
            ToolContext toolContext) {

        Long customerId = requireCustomerId(toolContext);
        String actorRole = requireActorRole(toolContext);

        if (fromAccountId != null && fromAccountId.equals(toAccountId)) {
            return "I can't propose a transfer to the same account.";
        }
        if (amount == null || amount.compareTo(MIN_AMOUNT) < 0) {
            return "I can't propose a transfer for that amount - it must be at least $0.01.";
        }

        List<AccountSummary> accounts = contextService.getAccountSummaries(customerId);
        Optional<AccountSummary> from = accounts.stream().filter(a -> a.accountId().equals(fromAccountId)).findFirst();
        Optional<AccountSummary> to = accounts.stream().filter(a -> a.accountId().equals(toAccountId)).findFirst();

        if (from.isEmpty() || to.isEmpty()) {
            return "I can't propose that transfer - one or both of those accounts aren't available to you.";
        }
        if (from.get().balance().compareTo(amount) < 0) {
            return "I can't propose that transfer - the source account's balance ($" + from.get().balance()
                    + ") is less than the requested amount.";
        }

        TransferRequest request = new TransferRequest(fromAccountId, toAccountId, amount, description);
        String summary = String.format("Transfer $%s from your %s account (#%d) to your %s account (#%d).",
                amount, from.get().accountType(), fromAccountId, to.get().accountType(), toAccountId);

        PendingAgentActionEntity proposal = confirmationGateService.propose(
                customerId, actorRole, PendingAgentActionType.TRANSFER, request, summary);

        pendingActionTracker.recordProposal(proposal.getToken(), PendingAgentActionType.TRANSFER.name(),
                proposal.getHumanSummary(), proposal.getExpiresAt());

        return summary + " This has NOT happened yet - it is awaiting the customer's explicit confirmation "
                + "in the chat window. Confirmation code: " + proposal.getToken();
    }

    private Long requireCustomerId(ToolContext toolContext) {
        Object customerId = toolContext.getContext().get(CUSTOMER_ID_KEY);
        if (!(customerId instanceof Long id)) {
            throw new IllegalStateException("Transfer tool invoked without a customerId in ToolContext");
        }
        return id;
    }

    private String requireActorRole(ToolContext toolContext) {
        Object actorRole = toolContext.getContext().get(ACTOR_ROLE_KEY);
        if (!(actorRole instanceof String role)) {
            throw new IllegalStateException("Transfer tool invoked without an actorRole in ToolContext");
        }
        return role;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw.cmd test -Dtest=TransferChatToolTest`
Expected: PASS (5/5 tests)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/group1/banking/service/impl/TransferChatTool.java \
        backend/src/test/java/com/group1/banking/service/impl/TransferChatToolTest.java
git commit -m "feat: add TransferChatTool - the agent's propose-only transfer tool"
```

---

## Task 5: `PendingActionTracker` + `PendingConfirmationView` + `ChatQueryResponse` field

**Files:**
- Create: `backend/src/main/java/com/group1/banking/service/impl/PendingActionTracker.java`
- Create: `backend/src/main/java/com/group1/banking/dto/chat/PendingConfirmationView.java`
- Modify: `backend/src/main/java/com/group1/banking/dto/chat/ChatQueryResponse.java`
- Test: `backend/src/test/java/com/group1/banking/service/impl/PendingActionTrackerTest.java`

**Interfaces:**
- Produces: `PendingActionTracker` (`@Component`) with `void reset()`, `void recordProposal(String token, String actionType, String summary, LocalDateTime expiresAt)`, `Optional<PendingConfirmationView> drainProposal()` (returns the view once, then clears itself - same drain-once shape as `SavingsChatCitationTracker.drainCitations()`). `PendingConfirmationView(String token, String actionType, String summary, LocalDateTime expiresAt)` with JSON property names `token`, `action_type`, `summary`, `expires_at`. `ChatQueryResponse` gains `getPendingConfirmation()`/`setPendingConfirmation(PendingConfirmationView)`, JSON property `pending_confirmation`; existing 4-arg constructor and other fields are unchanged.

- [ ] **Step 1: Write the failing test**

```java
package com.group1.banking.service.impl;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PendingActionTrackerTest {

    @Test
    void drainProposal_shouldReturnEmpty_whenNothingRecorded() {
        PendingActionTracker tracker = new PendingActionTracker();
        assertThat(tracker.drainProposal()).isEmpty();
    }

    @Test
    void drainProposal_shouldReturnRecordedProposalOnce() {
        PendingActionTracker tracker = new PendingActionTracker();
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(5);

        tracker.recordProposal("tok-1", "TRANSFER", "Transfer $50.00", expiresAt);

        Optional<com.group1.banking.dto.chat.PendingConfirmationView> first = tracker.drainProposal();
        assertThat(first).isPresent();
        assertThat(first.get().getToken()).isEqualTo("tok-1");
        assertThat(first.get().getActionType()).isEqualTo("TRANSFER");
        assertThat(first.get().getSummary()).isEqualTo("Transfer $50.00");
        assertThat(first.get().getExpiresAt()).isEqualTo(expiresAt);

        assertThat(tracker.drainProposal()).isEmpty();
    }

    @Test
    void reset_shouldClearAnyRecordedProposal() {
        PendingActionTracker tracker = new PendingActionTracker();
        tracker.recordProposal("tok-2", "TRANSFER", "Transfer $10.00", LocalDateTime.now().plusMinutes(5));

        tracker.reset();

        assertThat(tracker.drainProposal()).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw.cmd test -Dtest=PendingActionTrackerTest`
Expected: FAIL to compile - `PendingActionTracker` and `PendingConfirmationView` don't exist yet.

- [ ] **Step 3: Create `PendingConfirmationView`**

```java
package com.group1.banking.dto.chat;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/** The `pending_confirmation` block on a chat response - present only on a turn where
 * the agent proposed (but did not execute) a financial/account-changing action. */
public class PendingConfirmationView {

    @JsonProperty("token")
    private String token;

    @JsonProperty("action_type")
    private String actionType;

    @JsonProperty("summary")
    private String summary;

    @JsonProperty("expires_at")
    private LocalDateTime expiresAt;

    public PendingConfirmationView() {}

    public PendingConfirmationView(String token, String actionType, String summary, LocalDateTime expiresAt) {
        this.token = token;
        this.actionType = actionType;
        this.summary = summary;
        this.expiresAt = expiresAt;
    }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getActionType() { return actionType; }
    public void setActionType(String actionType) { this.actionType = actionType; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
}
```

- [ ] **Step 4: Create `PendingActionTracker`**

```java
package com.group1.banking.service.impl;

import com.group1.banking.dto.chat.PendingConfirmationView;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Carries "a mutating tool proposed an action this turn" out of the agentic
 * tool-calling loop so SavingsInsightChatService can attach it to the chat response
 * as structured data. Same reset-per-turn, drain-once shape as
 * {@link SavingsChatCitationTracker} - see that class for the concurrency caveat
 * this shares (a per-turn singleton is safe only because turns aren't processed
 * concurrently against the same bean instance today).
 */
@Component
public class PendingActionTracker {

    private String token;
    private String actionType;
    private String summary;
    private LocalDateTime expiresAt;

    public void reset() {
        token = null;
        actionType = null;
        summary = null;
        expiresAt = null;
    }

    public void recordProposal(String token, String actionType, String summary, LocalDateTime expiresAt) {
        this.token = token;
        this.actionType = actionType;
        this.summary = summary;
        this.expiresAt = expiresAt;
    }

    public Optional<PendingConfirmationView> drainProposal() {
        if (token == null) {
            return Optional.empty();
        }
        PendingConfirmationView view = new PendingConfirmationView(token, actionType, summary, expiresAt);
        reset();
        return Optional.of(view);
    }
}
```

- [ ] **Step 5: Add the field to `ChatQueryResponse`**

Add to `backend/src/main/java/com/group1/banking/dto/chat/ChatQueryResponse.java`, alongside the existing fields:

```java
    @JsonProperty("pending_confirmation")
    private PendingConfirmationView pendingConfirmation;
```

Add alongside the existing getters/setters (existing 4-arg constructor is unchanged - this field is populated via the setter after construction):

```java
    public PendingConfirmationView getPendingConfirmation() { return pendingConfirmation; }
    public void setPendingConfirmation(PendingConfirmationView pendingConfirmation) { this.pendingConfirmation = pendingConfirmation; }
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd backend && ./mvnw.cmd test -Dtest=PendingActionTrackerTest`
Expected: PASS (3/3 tests)

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/group1/banking/service/impl/PendingActionTracker.java \
        backend/src/main/java/com/group1/banking/dto/chat/PendingConfirmationView.java \
        backend/src/main/java/com/group1/banking/dto/chat/ChatQueryResponse.java \
        backend/src/test/java/com/group1/banking/service/impl/PendingActionTrackerTest.java
git commit -m "feat: add PendingActionTracker and pending_confirmation response field"
```

---

## Task 6: Wire the tool and tracker into the chat client and service

**Files:**
- Modify: `backend/src/main/java/com/group1/banking/config/ChatbotAiConfig.java`
- Modify: `backend/src/main/java/com/group1/banking/service/impl/SavingsInsightChatService.java`
- Modify: `backend/src/test/java/com/group1/banking/service/impl/SavingsInsightChatServiceTest.java`

**Interfaces:**
- Consumes: `TransferChatTool` (Task 4), `PendingActionTracker` (Task 5).
- Produces: `SavingsInsightChatService.ask(Long customerId, String actorRole, String rawMessage)` keeps its existing signature (no callers outside this class change), but its constructor gains a `PendingActionTracker` parameter, and a successful (`ANSWERED`/`FALLBACK`) response now carries `pendingConfirmation` when the agent proposed a transfer during that turn.

- [ ] **Step 1: Update the failing/changed tests first**

In `backend/src/test/java/com/group1/banking/service/impl/SavingsInsightChatServiceTest.java`:

Add a field and update `setUp()`:

```java
    private PendingActionTracker pendingActionTracker;
```

```java
        pendingActionTracker = new PendingActionTracker();
        service = new SavingsInsightChatService(
                guardrailService, chatClient, chatLogRepository, citationTracker, pendingActionTracker, auditService);
```

Add a new test, near `ask_whenPersonalDataUsed_returnsPersonalizedAnswer`:

```java
    @Test
    @DisplayName("When the transfer tool proposed a transfer this turn, the response carries pending_confirmation")
    void ask_whenTransferProposed_attachesPendingConfirmation() {
        java.time.LocalDateTime expiresAt = java.time.LocalDateTime.now().plusMinutes(5);
        stubChatReply("I've prepared that transfer for you to confirm.", () ->
                pendingActionTracker.recordProposal("tok-9", "TRANSFER", "Transfer $50.00 from Checking to Savings.", expiresAt));

        ChatQueryResponse response = service.ask(CUSTOMER_ID, ACTOR_ROLE, "transfer $50 from checking to savings");

        assertThat(response.getPendingConfirmation()).isNotNull();
        assertThat(response.getPendingConfirmation().getToken()).isEqualTo("tok-9");
        assertThat(response.getPendingConfirmation().getActionType()).isEqualTo("TRANSFER");
    }

    @Test
    @DisplayName("When nothing was proposed this turn, pending_confirmation is null")
    void ask_whenNothingProposed_leavesPendingConfirmationNull() {
        stubChatReply("You spent $312 on Food & Drink over the last 30 days.", () -> {
            citationTracker.recordCitation("Your recent transaction history");
            citationTracker.markPersonalDataUsed();
        });

        ChatQueryResponse response = service.ask(CUSTOMER_ID, ACTOR_ROLE, "where is my money going?");

        assertThat(response.getPendingConfirmation()).isNull();
    }
```

Add the import: `import com.group1.banking.dto.chat.ChatQueryResponse;` (if not already present via wildcard - check the existing import block first).

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./mvnw.cmd test -Dtest=SavingsInsightChatServiceTest`
Expected: FAIL to compile - the 6-arg constructor doesn't exist yet, `getPendingConfirmation()` doesn't exist on the response from this service's own wiring yet (it exists on the DTO from Task 5, but nothing populates it).

- [ ] **Step 3: Update `SavingsInsightChatService`**

Add the field, constructor parameter, and assignment:

```java
    private final PendingActionTracker pendingActionTracker;
```

```java
    public SavingsInsightChatService(SavingsChatGuardrailService guardrailService,
                                      ChatClient chatClient,
                                      ChatInteractionLogRepository chatLogRepository,
                                      SavingsChatCitationTracker citationTracker,
                                      PendingActionTracker pendingActionTracker,
                                      AuditService auditService) {
        this.guardrailService = guardrailService;
        this.chatClient = chatClient;
        this.chatLogRepository = chatLogRepository;
        this.citationTracker = citationTracker;
        this.pendingActionTracker = pendingActionTracker;
        this.auditService = auditService;
    }
```

Add `pendingActionTracker.reset();` next to the existing `citationTracker.reset();` call (both right before the `try` block that calls the model), and again next to the `citationTracker.reset();` inside the `catch` block.

Add `.toolContext(Map.of(CUSTOMER_ID_KEY, customerId, ACTOR_ROLE_KEY, actorRole))` in place of the current `.toolContext(Map.of(CUSTOMER_ID_KEY, customerId))` call, and add the constant:

```java
    private static final String ACTOR_ROLE_KEY = "actorRole";
```

At the end of the success path, replace:

```java
        return new ChatQueryResponse(reply, basedOn, limitedData, false);
```

with:

```java
        ChatQueryResponse response = new ChatQueryResponse(reply, basedOn, limitedData, false);
        pendingActionTracker.drainProposal().ifPresent(response::setPendingConfirmation);
        return response;
```

- [ ] **Step 4: Wire `TransferChatTool` into the `ChatClient` bean**

In `backend/src/main/java/com/group1/banking/config/ChatbotAiConfig.java`, add the import and parameter:

```java
import com.group1.banking.service.impl.TransferChatTool;
```

```java
    @Bean
    public ChatClient savingsInsightChatClient(ChatModel chatModel, SavingsChatTools savingsChatTools,
                                                TransferChatTool transferChatTool,
                                                ChatMemory chatMemory,
                                                @Value("${spring.ai.openai.chat.model}") String groqChatModel) {
```

Change `.defaultTools(savingsChatTools)` to `.defaultTools(savingsChatTools, transferChatTool)`.

Add this to the system prompt, right after the existing four "Decide for yourself which tools" bullets (before the blank line that follows them):

```
                        - A request to move money between the customer's own accounts: call the \
                        transfer proposal tool. It never moves money itself - it only prepares a \
                        transfer for the customer to confirm. After calling it, tell the customer \
                        the transfer is prepared and awaiting their confirmation; never say it is \
                        complete, and never call it again for the same request once you've already \
                        proposed it - let the customer confirm or ask again themselves.
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && ./mvnw.cmd test -Dtest=SavingsInsightChatServiceTest`
Expected: PASS (all tests, including the two new ones)

Run: `cd backend && ./mvnw.cmd compile` (confirms `ChatbotAiConfig` still compiles with the new bean parameter)
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/group1/banking/config/ChatbotAiConfig.java \
        backend/src/main/java/com/group1/banking/service/impl/SavingsInsightChatService.java \
        backend/src/test/java/com/group1/banking/service/impl/SavingsInsightChatServiceTest.java
git commit -m "feat: wire TransferChatTool and PendingActionTracker into the chat agent"
```

---

## Task 7: `AgentActionConfirmationController`

**Files:**
- Create: `backend/src/main/java/com/group1/banking/controller/AgentActionConfirmationController.java`
- Test: `backend/src/test/java/com/group1/banking/controller/AgentActionConfirmationControllerTest.java`

**Interfaces:**
- Consumes: `ConfirmationGateService.confirmAndConsume(String token, Long customerId, String actorRole)` (Task 3), `MonetaryOperationService.transfer(TransferRequest request, String idempotencyKey)` returning `OperationResult(HttpStatus status, Object body)` (existing), `tools.jackson.databind.json.JsonMapper` (existing bean).
- Produces: `POST /api/chat/confirmations/{token}`, authenticated, no request body. Success: same status/body shape `POST /api/accounts/transfer` already returns. Errors: 404/409/410 via the exceptions `ConfirmationGateService` already throws (Task 3), handled globally.

- [ ] **Step 1: Write the failing test**

```java
package com.group1.banking.controller;

import com.group1.banking.dto.customer.OperationResult;
import com.group1.banking.entity.PendingAgentActionEntity;
import com.group1.banking.entity.PendingAgentActionStatus;
import com.group1.banking.entity.PendingAgentActionType;
import com.group1.banking.exception.ConflictException;
import com.group1.banking.exception.GoneException;
import com.group1.banking.exception.NotFoundException;
import com.group1.banking.repository.UserRepository;
import com.group1.banking.security.JwtService;
import com.group1.banking.service.ConfirmationGateService;
import com.group1.banking.service.impl.MonetaryOperationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentActionConfirmationController.class)
@AutoConfigureMockMvc(addFilters = false)
class AgentActionConfirmationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConfirmationGateService confirmationGateService;

    @MockitoBean
    private MonetaryOperationService monetaryOperationService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    @WithCustomUser(customerId = 42L)
    void confirm_shouldExecuteTransfer_whenTokenValid() throws Exception {
        PendingAgentActionEntity resolved = transferEntity("tok-1");
        when(confirmationGateService.confirmAndConsume(eq("tok-1"), eq(42L), eq("CUSTOMER"))).thenReturn(resolved);
        when(monetaryOperationService.transfer(any(), eq("tok-1")))
                .thenReturn(new OperationResult(HttpStatus.OK, Map.of("message", "Transfer complete")));

        mockMvc.perform(post("/api/chat/confirmations/tok-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Transfer complete"));
    }

    @Test
    @WithCustomUser(customerId = 42L)
    void confirm_shouldReturn404_whenTokenNotFound() throws Exception {
        when(confirmationGateService.confirmAndConsume(eq("missing"), eq(42L), eq("CUSTOMER")))
                .thenThrow(new NotFoundException("CONFIRMATION_NOT_FOUND", "No pending action found for this token.", null));

        mockMvc.perform(post("/api/chat/confirmations/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONFIRMATION_NOT_FOUND"));
    }

    @Test
    @WithCustomUser(customerId = 42L)
    void confirm_shouldReturn409_whenAlreadyResolved() throws Exception {
        when(confirmationGateService.confirmAndConsume(eq("tok-2"), eq(42L), eq("CUSTOMER")))
                .thenThrow(new ConflictException("CONFIRMATION_ALREADY_RESOLVED", "This action has already been executed.", null));

        mockMvc.perform(post("/api/chat/confirmations/tok-2"))
                .andExpect(status().isConflict());
    }

    @Test
    @WithCustomUser(customerId = 42L)
    void confirm_shouldReturn410_whenExpired() throws Exception {
        when(confirmationGateService.confirmAndConsume(eq("tok-3"), eq(42L), eq("CUSTOMER")))
                .thenThrow(new GoneException("CONFIRMATION_EXPIRED", "This confirmation has expired.", null));

        mockMvc.perform(post("/api/chat/confirmations/tok-3"))
                .andExpect(status().isGone());
    }

    private PendingAgentActionEntity transferEntity(String token) {
        PendingAgentActionEntity entity = new PendingAgentActionEntity();
        entity.setToken(token);
        entity.setCustomerId(42L);
        entity.setActionType(PendingAgentActionType.TRANSFER);
        entity.setParametersJson("{\"fromAccountId\":1,\"toAccountId\":2,\"amount\":50.00,\"description\":null}");
        entity.setHumanSummary("Transfer $50.00");
        entity.setStatus(PendingAgentActionStatus.EXECUTED);
        entity.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        return entity;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw.cmd test -Dtest=AgentActionConfirmationControllerTest`
Expected: FAIL to compile - `AgentActionConfirmationController` doesn't exist yet.

- [ ] **Step 3: Write the implementation**

```java
package com.group1.banking.controller;

import com.group1.banking.dto.customer.OperationResult;
import com.group1.banking.dto.customer.TransferRequest;
import com.group1.banking.entity.PendingAgentActionEntity;
import com.group1.banking.exception.PermissionDeniedException;
import com.group1.banking.security.CustomUserPrincipal;
import com.group1.banking.service.ConfirmationGateService;
import com.group1.banking.service.impl.MonetaryOperationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Executes an agent-proposed action once the customer has explicitly confirmed it
 * (AC4). This is the ONLY path by which a proposal from {@code TransferChatTool} (or
 * any future mutating chat tool) can actually take effect - the model's tool-calling
 * loop itself has no way to reach this endpoint.
 */
@RestController
@RequestMapping("/api/chat/confirmations")
@PreAuthorize("isAuthenticated()")
public class AgentActionConfirmationController {

    private static final String DEFAULT_ACTOR_ROLE = "UNKNOWN";
    private static final String ROLE_PREFIX = "ROLE_";

    private final ConfirmationGateService confirmationGateService;
    private final MonetaryOperationService monetaryOperationService;
    private final JsonMapper objectMapper;

    public AgentActionConfirmationController(ConfirmationGateService confirmationGateService,
                                              MonetaryOperationService monetaryOperationService,
                                              JsonMapper objectMapper) {
        this.confirmationGateService = confirmationGateService;
        this.monetaryOperationService = monetaryOperationService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{token}")
    public ResponseEntity<Object> confirm(@PathVariable String token,
                                           @AuthenticationPrincipal CustomUserPrincipal principal) {
        CustomUserPrincipal caller = extractPrincipal(principal);
        String actorRole = extractRole(caller);

        PendingAgentActionEntity resolved =
                confirmationGateService.confirmAndConsume(token, caller.getCustomerId(), actorRole);

        OperationResult result = switch (resolved.getActionType()) {
            case TRANSFER -> executeTransfer(resolved);
        };

        return ResponseEntity.status(result.status()).body(result.body());
    }

    private OperationResult executeTransfer(PendingAgentActionEntity resolved) {
        TransferRequest request = objectMapper.readValue(resolved.getParametersJson(), TransferRequest.class);
        return monetaryOperationService.transfer(request, resolved.getToken());
    }

    private CustomUserPrincipal extractPrincipal(CustomUserPrincipal principal) {
        if (principal == null) {
            throw new PermissionDeniedException("AUTHENTICATION");
        }
        return principal;
    }

    private String extractRole(CustomUserPrincipal principal) {
        return principal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith(ROLE_PREFIX))
                .map(authority -> authority.substring(ROLE_PREFIX.length()))
                .findFirst()
                .orElse(DEFAULT_ACTOR_ROLE);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw.cmd test -Dtest=AgentActionConfirmationControllerTest`
Expected: PASS (4/4 tests)

- [ ] **Step 5: Run the full backend suite before moving to the frontend**

Run: `cd backend && ./mvnw.cmd test`
Expected: BUILD SUCCESS, no regressions in `SavingsInsightChatServiceTest`, `SavingsInsightChatControllerTest`, or any other existing test.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/group1/banking/controller/AgentActionConfirmationController.java \
        backend/src/test/java/com/group1/banking/controller/AgentActionConfirmationControllerTest.java
git commit -m "feat: add POST /api/chat/confirmations/{token} to execute confirmed agent actions"
```

---

## Task 8: Frontend - confirmation card in the chat widget

**Files:**
- Modify: `src/api/chat.js`
- Modify: `src/hooks/useSavingsChat.js`
- Modify: `src/components/ChatWidget.jsx`
- Modify: `src/test/components/ChatWidget.test.jsx`

**Interfaces:**
- Consumes: `POST /api/chat/confirmations/{token}` (Task 7); `pending_confirmation: {token, action_type, summary, expires_at}` on the existing `/api/chat/savings-insights` response (Task 5/6).
- Produces: `confirmAgentAction(token)` in `src/api/chat.js`; `useConfirmAgentAction()` hook in `src/hooks/useSavingsChat.js`; a rendered confirmation card in `ChatWidget` with Confirm/Dismiss controls.

- [ ] **Step 1: Read the current test file to see the existing assertions this must not break**

Run: `cat src/test/components/ChatWidget.test.jsx` and confirm the existing tests only assert on `response`, `based_on`, `limited_data`, `blocked` - none of them reference a confirmation card yet, so this task is purely additive.

- [ ] **Step 2: Write the failing test**

Add to `src/test/components/ChatWidget.test.jsx` (mirror the existing test setup in that file for how `askSavingsInsight` is mocked - use the same mocking approach already present, e.g. `vi.mock('../../api/chat')`):

```jsx
import { confirmAgentAction } from '../../api/chat';

// ... inside the existing describe block, alongside the other tests:

it('renders a confirmation card when the response includes pending_confirmation, and confirms it', async () => {
  askSavingsInsight.mockResolvedValueOnce({
    response: "I've prepared that transfer for you to confirm.",
    based_on: [],
    limited_data: false,
    blocked: false,
    pending_confirmation: {
      token: 'tok-1',
      action_type: 'TRANSFER',
      summary: 'Transfer $50.00 from your CHECKING account (#1) to your SAVINGS account (#2).',
      expires_at: '2026-08-26T12:05:00'
    }
  });
  confirmAgentAction.mockResolvedValueOnce({ message: 'Transfer complete' });

  render(<ChatWidget />);
  fireEvent.click(screen.getByLabelText(/open savings assistant chat/i));
  fireEvent.change(screen.getByLabelText(/message/i), { target: { value: 'transfer $50 from checking to savings' } });
  fireEvent.click(screen.getByText('Send'));

  expect(await screen.findByText(/Transfer \$50\.00 from your CHECKING account/i)).toBeInTheDocument();
  const confirmButton = screen.getByRole('button', { name: /confirm/i });

  fireEvent.click(confirmButton);

  expect(await screen.findByText(/Transfer complete/i)).toBeInTheDocument();
  expect(confirmAgentAction).toHaveBeenCalledWith('tok-1');
});
```

(Adjust the exact mocking/import lines to match whatever pattern the existing tests in this file already use for `askSavingsInsight` - read the file first, per Step 1, and follow it exactly rather than introducing a second mocking style.)

- [ ] **Step 3: Run test to verify it fails**

Run: `npx vitest run src/test/components/ChatWidget.test.jsx`
Expected: FAIL - `confirmAgentAction` doesn't exist, and no confirmation card is rendered yet.

- [ ] **Step 4: Add `confirmAgentAction` to `src/api/chat.js`**

```javascript
export async function confirmAgentAction(token) {
  const response = await accountApiClient.post(`/api/chat/confirmations/${token}`);
  return response.data;
}
```

- [ ] **Step 5: Add `useConfirmAgentAction` to `src/hooks/useSavingsChat.js`**

```javascript
import { useMutation } from '@tanstack/react-query';
import { askSavingsInsight, confirmAgentAction } from '../api/chat';

export function useSavingsChat() {
  return useMutation({
    mutationFn: askSavingsInsight,
    throwOnError: false
  });
}

export function useConfirmAgentAction() {
  return useMutation({
    mutationFn: confirmAgentAction,
    throwOnError: false
  });
}
```

- [ ] **Step 6: Render and wire the confirmation card in `ChatWidget.jsx`**

Add the import:

```javascript
import { useConfirmAgentAction } from '../hooks/useSavingsChat';
import { mapAxiosError } from '../api/axiosClient';
```

(`mapAxiosError` is already imported - just add the new hook import alongside it.)

In the component body, add the mutation hook next to `chatMutation`:

```javascript
  const confirmMutation = useConfirmAgentAction();
```

In `onSuccess` of `chatMutation.mutate`, carry the new field into the stored message:

```javascript
            pendingConfirmation: data?.pending_confirmation || null
```

(add this as a new key in the object literal that already has `id`, `role`, `text`, `basedOn`, `limitedData`, `blocked`).

Add a handler function near `handleSubmit`:

```javascript
  function handleConfirmAction(messageId, token) {
    confirmMutation.mutate(token, {
      onSuccess: (data) => {
        setMessages((current) => current.map((message) =>
          message.id === messageId
            ? { ...message, pendingConfirmation: null, confirmedResultText: data?.message || 'This action was completed.' }
            : message
        ));
      },
      onError: (error) => {
        const mapped = mapAxiosError(error);
        setMessages((current) => current.map((message) =>
          message.id === messageId
            ? { ...message, pendingConfirmation: { ...message.pendingConfirmation, errorText: mapped.message || 'This confirmation could not be completed.' } }
            : message
        ));
      }
    });
  }

  function handleDismissAction(messageId) {
    setMessages((current) => current.map((message) =>
      message.id === messageId ? { ...message, pendingConfirmation: null } : message
    ));
  }
```

In the message rendering block, right after the existing `chat-bubble-note` block for `limitedData`, add:

```jsx
                  {message.role === 'assistant' && message.confirmedResultText && (
                    <p className="chat-bubble-note">{message.confirmedResultText}</p>
                  )}
                  {message.role === 'assistant' && message.pendingConfirmation && (
                    <div className="chat-confirmation-card">
                      <p className="chat-confirmation-summary">{message.pendingConfirmation.summary}</p>
                      {message.pendingConfirmation.errorText && (
                        <p className="chat-confirmation-error">{message.pendingConfirmation.errorText}</p>
                      )}
                      <div className="chat-confirmation-actions">
                        <button
                          type="button"
                          onClick={() => handleConfirmAction(message.id, message.pendingConfirmation.token)}
                          disabled={confirmMutation.isPending}
                        >
                          Confirm
                        </button>
                        <button
                          type="button"
                          className="chat-confirmation-dismiss"
                          onClick={() => handleDismissAction(message.id)}
                          disabled={confirmMutation.isPending}
                        >
                          Dismiss
                        </button>
                      </div>
                    </div>
                  )}
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `npx vitest run src/test/components/ChatWidget.test.jsx`
Expected: PASS, including the new confirmation-card test, with no regressions in the existing three tests.

- [ ] **Step 8: Commit**

```bash
git add src/api/chat.js src/hooks/useSavingsChat.js src/components/ChatWidget.jsx src/test/components/ChatWidget.test.jsx
git commit -m "feat: render agent confirmation card in the chat widget"
```

---

## Final Verification

- [ ] Run the full backend suite: `cd backend && ./mvnw.cmd test` - expect BUILD SUCCESS, zero regressions.
- [ ] Run the full frontend suite: `npx vitest run` - expect all green, zero regressions.
- [ ] Manual smoke test (per `specs/002-agent-confirmation-gate/spec.md` User Stories 1-2): start `mcp-test-server` and the backend as usual (see earlier session notes for the two-terminal startup order - unrelated to this feature, just needed for the app to boot), log in as the seeded `goalSaver` persona (`seed.goalsaver@voltio.test`), which has two open accounts by design - see `backend/src/main/resources/personas/voltio-personas.yaml`, ask the chatbot to transfer money between them, confirm neither balance changes yet and a confirmation card appears, click Confirm, and confirm both balances update and the card is replaced with a success message.
- [ ] Confirm an audit row exists for both the proposal and the confirmation (query `audit_log` for `action IN ('AGENT_ACTION_PROPOSED','AGENT_ACTION_CONFIRMED')`, per spec User Story 5).
