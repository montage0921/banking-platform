package com.group1.banking.repository;

import com.group1.banking.entity.AuditLogEntity;
import com.group1.banking.entity.AuditEventType;
import com.group1.banking.entity.AuditOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AuditLogRepositoryTest {

    @Autowired
    private AuditLogRepository repository;

    @Test
    void save_shouldPersistAuditLog() {
        AuditLogEntity saved = repository.save(buildLog("user-001"));
        assertThat(saved.getLogId()).isNotNull();
    }

    @Test
    void save_shouldSetTimestampAutomatically() {
        AuditLogEntity saved = repository.save(buildLog("user-002"));
        assertThat(saved.getTimestamp()).isNotNull();
    }

    @Test
    void findById_shouldReturnLog_whenExists() {
        AuditLogEntity saved = repository.save(buildLog("user-003"));

        Optional<AuditLogEntity> found = repository.findById(saved.getLogId());
        assertThat(found).isPresent();
        assertThat(found.get().getActorId()).isEqualTo("user-003");
    }

    @Test
    void findById_shouldReturnEmpty_whenNotFound() {
        Optional<AuditLogEntity> found = repository.findById(99999L);
        assertThat(found).isEmpty();
    }

    @Test
    void save_shouldPersistAllFields() {
        AuditLogEntity log = buildLog("user-004");
        log.setSubjectId("account/1001");
        AuditLogEntity saved = repository.save(log);

        Optional<AuditLogEntity> found = repository.findById(saved.getLogId());
        assertThat(found).isPresent();
        assertThat(found.get().getEventType()).isEqualTo(AuditEventType.FUNDS_TRANSFERRED);
        assertThat(found.get().getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(found.get().getActorType()).isEqualTo(com.group1.banking.enums.RoleName.RETAIL_CUSTOMER);
        assertThat(found.get().getSubjectType()).isEqualTo("ACCOUNT");
        assertThat(found.get().getSubjectId()).isEqualTo("account/1001");
    }

    private AuditLogEntity buildLog(String actorId) {
        AuditLogEntity log = new AuditLogEntity();
        log.setActorId(actorId);
        log.setActorType(com.group1.banking.enums.RoleName.RETAIL_CUSTOMER);
        log.setEventType(AuditEventType.FUNDS_TRANSFERRED);
        log.setSubjectType("ACCOUNT");
        log.setOutcome(AuditOutcome.SUCCESS);
        return log;
    }
}
