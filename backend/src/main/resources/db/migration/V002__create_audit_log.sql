-- Creates canonical audit_log table for AuditLogEntity
CREATE TABLE IF NOT EXISTS audit_log (
  log_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_type VARCHAR(60) NOT NULL,
  source_feature VARCHAR(100),
  actor_type VARCHAR(50) NOT NULL,
  actor_id VARCHAR(50) NOT NULL,
  subject_type VARCHAR(60) NOT NULL,
  subject_id VARCHAR(100),
  outcome VARCHAR(20) NOT NULL,
  event_details CLOB,
  timestamp TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_al_actor_id ON audit_log(actor_id);
CREATE INDEX IF NOT EXISTS idx_al_subject ON audit_log(subject_type, subject_id);
CREATE INDEX IF NOT EXISTS idx_al_timestamp ON audit_log(timestamp);
