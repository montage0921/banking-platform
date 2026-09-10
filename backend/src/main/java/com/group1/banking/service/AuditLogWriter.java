package com.group1.banking.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.group1.banking.entity.AuditLogEntity;
import com.group1.banking.repository.AuditLogRepository;

/**
 * Writes audit log entries in a separate transaction so caller rollbacks
 * do not affect persistence of audit entries.
 */
@Component
public class AuditLogWriter {

    private final AuditLogRepository auditLogRepository;
    private static final Logger log = LoggerFactory.getLogger(AuditLogWriter.class);

    public AuditLogWriter(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditLogEntity save(AuditLogEntity entry) {
        AuditLogEntity saved = auditLogRepository.save(entry);
        log.info("Audit saved: {} {} {} {}", saved.getLogId(), saved.getEventType(), saved.getActorId(), saved.getOutcome());
        return saved;
    }
}
