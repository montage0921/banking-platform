package com.group1.banking.service;

import com.group1.banking.entity.AuditEventType;
import com.group1.banking.entity.AuditOutcome;
import com.group1.banking.entity.PendingAgentActionEntity;
import com.group1.banking.entity.PendingAgentActionStatus;
import com.group1.banking.entity.PendingAgentActionType;
import com.group1.banking.enums.RoleName;
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
        private static final String ACTOR_ROLE = "RETAIL_CUSTOMER";

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

        verify(auditService).log(eq(AuditEventType.OTHER), eq("AGENT_ACTION_CONFIRMATION"), eq(RoleName.RETAIL_CUSTOMER),
                eq("42"), eq("PENDING_AGENT_ACTION"), eq(result.getToken()), eq(AuditOutcome.SUCCESS),
                eq("AGENT_ACTION_PROPOSED:PROPOSED"));
    }

    @Test
    void confirmAndConsume_shouldExecuteAndAudit_whenValidAndOwned() {
        PendingAgentActionEntity pending = pendingEntity("tok-1", PendingAgentActionStatus.PENDING,
                LocalDateTime.now().plusMinutes(5));
        when(repository.findById("tok-1")).thenReturn(Optional.of(pending));

        PendingAgentActionEntity result = service.confirmAndConsume("tok-1", CUSTOMER_ID, ACTOR_ROLE);

        assertThat(result.getStatus()).isEqualTo(PendingAgentActionStatus.EXECUTED);
        verify(auditService).log(eq(AuditEventType.OTHER), eq("AGENT_ACTION_CONFIRMATION"), eq(RoleName.RETAIL_CUSTOMER),
                eq("42"), eq("PENDING_AGENT_ACTION"), eq("tok-1"), eq(AuditOutcome.SUCCESS),
                eq("AGENT_ACTION_CONFIRMED:CONFIRMED"));
    }

    @Test
    void confirmAndConsume_shouldThrowNotFound_whenTokenMissing() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmAndConsume("missing", CUSTOMER_ID, ACTOR_ROLE))
                .isInstanceOf(NotFoundException.class);
        verify(auditService).log(eq(AuditEventType.OTHER), eq("AGENT_ACTION_CONFIRMATION"), eq(RoleName.RETAIL_CUSTOMER),
                eq("42"), eq("PENDING_AGENT_ACTION"), eq("missing"), eq(AuditOutcome.DENIED),
                eq("AGENT_ACTION_DENIED:NOT_FOUND"));
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
        verify(auditService).log(eq(AuditEventType.OTHER), eq("AGENT_ACTION_CONFIRMATION"), eq(RoleName.RETAIL_CUSTOMER),
                eq("42"), eq("PENDING_AGENT_ACTION"), eq("tok-3"), eq(AuditOutcome.DENIED),
                eq("AGENT_ACTION_DENIED:ALREADY_RESOLVED"));
    }

    @Test
    void confirmAndConsume_shouldThrowGoneAndMarkExpired_whenPastExpiry() {
        PendingAgentActionEntity pending = pendingEntity("tok-4", PendingAgentActionStatus.PENDING,
                LocalDateTime.now().minusMinutes(1));
        when(repository.findById("tok-4")).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.confirmAndConsume("tok-4", CUSTOMER_ID, ACTOR_ROLE))
                .isInstanceOf(GoneException.class);
        assertThat(pending.getStatus()).isEqualTo(PendingAgentActionStatus.EXPIRED);
        verify(auditService).log(eq(AuditEventType.OTHER), eq("AGENT_ACTION_CONFIRMATION"), eq(RoleName.RETAIL_CUSTOMER),
                eq("42"), eq("PENDING_AGENT_ACTION"), eq("tok-4"), eq(AuditOutcome.DENIED),
                eq("AGENT_ACTION_DENIED:EXPIRED"));
    }

    @Test
    void confirmAndConsume_doesNotFailWhenAuditLoggingThrows() {
        PendingAgentActionEntity pending = pendingEntity("tok-5", PendingAgentActionStatus.PENDING,
                LocalDateTime.now().plusMinutes(5));
        when(repository.findById("tok-5")).thenReturn(Optional.of(pending));
        org.mockito.Mockito.doThrow(new RuntimeException("audit down"))
                .when(auditService).log(any(), any(), any(), any(), any(), any(), any(), any());

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
