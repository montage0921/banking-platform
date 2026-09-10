package com.group1.banking.service;

import com.group1.banking.entity.AuditEventType;
import com.group1.banking.entity.AuditLogEntity;
import com.group1.banking.entity.AuditOutcome;
import com.group1.banking.enums.RoleName;
import com.group1.banking.repository.AuditLogRepository;
import com.group1.banking.service.AuditLogWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Audit service for logging all significant operations. (T012)
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;
    private final AuditLogWriter auditLogWriter;

    public AuditService(AuditLogRepository auditLogRepository, AuditLogWriter auditLogWriter) {
        this.auditLogRepository = auditLogRepository;
        this.auditLogWriter = auditLogWriter;
    }

    public void log(String actorId, String actorRole, String action,
                    String resourceType, String resourceId, String outcome) {
        AuditEventType eventType = AuditEventType.fromString(action);
        RoleName actorRoleEnum = RoleName.RETAIL_CUSTOMER;
        if (actorRole != null) {
            String candidate = actorRole.replace("ROLE_", "").toUpperCase();
            try {
                actorRoleEnum = RoleName.valueOf(candidate);
            } catch (IllegalArgumentException ignored) {
                actorRoleEnum = RoleName.RETAIL_CUSTOMER;
            }
        }
        this.log(eventType, null, actorRoleEnum, actorId, resourceType, resourceId,
                    AuditOutcome.fromString(outcome), null);
    }
    public void log(AuditEventType eventType,
                    String sourceFeature,
                    RoleName actorType,
                    String actorId,
                    String subjectType,
                    String subjectId,
                    AuditOutcome outcome,
                    String eventDetails) {
        AuditLogEntity entry = new AuditLogEntity();
        entry.setEventType(eventType == null ? AuditEventType.OTHER : eventType);
        entry.setSourceFeature(sourceFeature);
        entry.setActorType(actorType == null ? RoleName.RETAIL_CUSTOMER : actorType);
        entry.setActorId(actorId == null ? "SYSTEM" : actorId);
        entry.setSubjectType(subjectType == null ? "UNKNOWN" : subjectType);
        entry.setSubjectId(subjectId);
        entry.setOutcome(outcome == null ? AuditOutcome.ERROR : outcome);
        entry.setEventDetails(eventDetails);
        try {
            auditLogWriter.save(entry);
        } catch (Exception ex) {
            log.error("Failed to save audit entry: {} {} {}", eventType, subjectType, subjectId, ex);
        }
    }
}
