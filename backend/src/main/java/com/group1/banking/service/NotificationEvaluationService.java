package com.group1.banking.service;

import com.group1.banking.dto.request.NotificationEventRequest;
import com.group1.banking.dto.response.NotificationDecisionResponse;
import com.group1.banking.entity.*;
import com.group1.banking.exception.BusinessStateException;
import com.group1.banking.exception.SemanticValidationException;
import com.group1.banking.mapper.NotificationDecisionMapper;
import com.group1.banking.repository.*;
import org.springframework.stereotype.Service;
import com.group1.banking.entity.AuditEventType;
import com.group1.banking.enums.RoleName;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

/**
 * Notification evaluation service. (T074)
 */
@Service
public class NotificationEvaluationService {

    private static final Set<String> MANDATORY_EVENTS = Set.of(
            "StandingOrderFailure", "UnusualAccountActivity");
    private static final Set<String> OPTIONAL_EVENTS = Set.of(
            "StatementAvailability", "StandingOrderCreation");
    private static final Set<String> ALL_EVENTS;

    static {
        ALL_EVENTS = new java.util.HashSet<>();
        ALL_EVENTS.addAll(MANDATORY_EVENTS);
        ALL_EVENTS.addAll(OPTIONAL_EVENTS);
    }

    private final NotificationDecisionRepository notificationDecisionRepository;
    private final NotificationPreferenceRepository notificationPreferenceRepository;
    private final AccountRepository accountRepository;
    private final NotificationDecisionMapper mapper;
    private final AuditService auditService;

    public NotificationEvaluationService(NotificationDecisionRepository notificationDecisionRepository,
                                          NotificationPreferenceRepository notificationPreferenceRepository,
                                          AccountRepository accountRepository,
                                          NotificationDecisionMapper mapper,
                                          AuditService auditService) {
        this.notificationDecisionRepository = notificationDecisionRepository;
        this.notificationPreferenceRepository = notificationPreferenceRepository;
        this.accountRepository = accountRepository;
        this.mapper = mapper;
        this.auditService = auditService;
    }

    /**
     * Evaluates a notification event and persists the decision. (T074)
     */
    @Transactional
    public NotificationDecisionResponse evaluate(NotificationEventRequest req) {
        // Duplicate eventId check
        if (notificationDecisionRepository.existsByEventId(req.getEventId())) {
            throw new BusinessStateException("Duplicate event ID", "ERR_DUPLICATE_EVENT");
        }

        // Validate event type
        if (!ALL_EVENTS.contains(req.getEventType())) {
            throw new SemanticValidationException("Unknown event type: " + req.getEventType(),
                    "ERR_UNKNOWN_EVENT_TYPE", "eventType");
        }

        // Validate customer-account linkage
        Account account = accountRepository.findById(req.getAccountId()).orElseThrow(
                () -> new SemanticValidationException("Account not found", "ERR_CUSTOMER_ACCOUNT_MISMATCH", "accountId"));
        Long owningCustomerId = account.getCustomer().getCustomerId();
        if (!owningCustomerId.equals(req.getCustomerId())) {
            throw new SemanticValidationException("Customer not linked to account",
                    "ERR_CUSTOMER_ACCOUNT_MISMATCH", "customerId");
        }

        boolean isMandatory = MANDATORY_EVENTS.contains(req.getEventType());
        NotificationDecision decision;
        String decisionReason;
        boolean mandatoryOverride = false;

        if (isMandatory) {
            // Mandatory event — always RAISED; check for opt-out to set mandatoryOverride
            Optional<NotificationPreferenceEntity> pref = notificationPreferenceRepository
                    .findByCustomerIdAndEventType(req.getCustomerId(), req.getEventType());
            if (pref.isPresent() && !pref.get().isOptedIn()) {
                mandatoryOverride = true;
                decisionReason = "Mandatory event: customer opted-out overridden";
            } else {
                decisionReason = "Mandatory event: always raised";
            }
            decision = NotificationDecision.RAISED;
        } else {
            // Optional event — depends on preference
            Optional<NotificationPreferenceEntity> pref = notificationPreferenceRepository
                    .findByCustomerIdAndEventType(req.getCustomerId(), req.getEventType());
            if (pref.isPresent() && !pref.get().isOptedIn()) {
                decision = NotificationDecision.SUPPRESSED;
                decisionReason = "Optional event: customer opted out";
            } else {
                decision = NotificationDecision.RAISED;
                decisionReason = "Optional event: customer opted in (or no preference = default open)";
            }
        }

        // Parse businessTimestamp
        LocalDateTime businessTimestamp;
        try {
            businessTimestamp = LocalDateTime.parse(req.getBusinessTimestamp()
                    .replace("Z", "").replace("T", "T"));
        } catch (Exception e) {
            businessTimestamp = LocalDateTime.now();
        }

        // Persist decision
        NotificationDecisionEntity entity = new NotificationDecisionEntity();
        entity.setEventId(req.getEventId());
        entity.setEventType(req.getEventType());
        entity.setAccountId(req.getAccountId());
        entity.setCustomerId(req.getCustomerId());
        entity.setBusinessTimestamp(businessTimestamp);
        entity.setPayload(req.getPayload());
        entity.setDecision(decision);
        entity.setDecisionReason(decisionReason);
        entity.setMandatoryOverride(mandatoryOverride);
        notificationDecisionRepository.save(entity);

        auditService.log(AuditEventType.NOTIFICATION_EVALUATE,
            "notifications",
            RoleName.BANK_ADMINISTRATOR,
            "-1",
            "NOTIFICATION",
            req.getEventId(),
            AuditOutcome.SUCCESS,
            null);

        return mapper.toResponse(entity);
    }

    /**
     * Internal evaluation triggered by scheduler (no HTTP request context). (T054)
     */
    public void evaluateInternal(String eventType, Long accountId, Long customerId,
                                  String businessTimestamp, String payload) {
        NotificationEventRequest req = new NotificationEventRequest();
        req.setEventId(java.util.UUID.randomUUID().toString());
        req.setEventType(eventType);
        req.setAccountId(accountId);
        req.setCustomerId(customerId);
        req.setBusinessTimestamp(businessTimestamp);
        req.setPayload(payload);
        try {
            evaluate(req);
        } catch (Exception e) {
                // Log but do not propagate — scheduler failure notification is best-effort
                auditService.log(AuditEventType.NOTIFICATION_INTERNAL_FAILED,
                    "notifications",
                    RoleName.BANK_ADMINISTRATOR,
                    "-1",
                    "NOTIFICATION",
                    eventType,
                    AuditOutcome.ERROR,
                    null);
        }
    }
}
