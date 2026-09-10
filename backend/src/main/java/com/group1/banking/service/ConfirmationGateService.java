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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
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
    private static final String AUDIT_SOURCE_FEATURE = "AGENT_ACTION_CONFIRMATION";
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
        try {
            entity.setParametersJson(objectMapper.writeValueAsString(parameters));
        } catch (JacksonException ex) {
            throw new IllegalStateException(
                    "Failed to serialize agent action parameters for customer " + customerId, ex);
        }
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
            // The 6-arg AuditService.log(...) shim maps its "outcome" string straight
            // through AuditOutcome.fromString(), which only recognizes SUCCESS/DENIED/
            // ERROR - every other value (PROPOSED, CONFIRMED, NOT_FOUND, ...) silently
            // fell through to ERROR. Calling the richer 8-arg overload directly lets us
            // map our fine-grained resolution reasons onto the right coarse outcome
            // while keeping the original reason readable in eventDetails.
            auditService.log(AuditEventType.OTHER, AUDIT_SOURCE_FEATURE, roleFor(actorRole),
                    customerId.toString(), RESOURCE_TYPE, token, outcomeFor(outcome), action + ":" + outcome);
        } catch (Exception ex) {
            log.error("Failed to write audit log entry for agent action token {}", token, ex);
        }
    }

    /**
     * Maps this service's fine-grained resolution reasons onto the shared, coarse
     * {@link AuditOutcome} vocabulary. The original reason is not lost - it is preserved
     * verbatim in the audit row's eventDetails via {@link #audit}.
     */
    private static AuditOutcome outcomeFor(String outcome) {
        return switch (outcome) {
            case "PROPOSED", "CONFIRMED" -> AuditOutcome.SUCCESS;
            case "NOT_FOUND", "ALREADY_RESOLVED", "EXPIRED" -> AuditOutcome.DENIED;
            default -> AuditOutcome.ERROR;
        };
    }

    private static RoleName roleFor(String actorRole) {
        if (actorRole == null) {
            return RoleName.RETAIL_CUSTOMER;
        }
        try {
            return RoleName.valueOf(actorRole.replace("ROLE_", "").toUpperCase());
        } catch (IllegalArgumentException ex) {
            return RoleName.RETAIL_CUSTOMER;
        }
    }
}
