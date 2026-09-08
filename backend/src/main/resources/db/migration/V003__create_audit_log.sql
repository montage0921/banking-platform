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

-- Repair databases created before event_type was added to audit_log.
ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS event_type VARCHAR(60) DEFAULT 'OTHER';

CREATE INDEX IF NOT EXISTS idx_al_actor_id ON audit_log(actor_id);
CREATE INDEX IF NOT EXISTS idx_al_subject ON audit_log(subject_type, subject_id);
CREATE INDEX IF NOT EXISTS idx_al_timestamp ON audit_log(timestamp);

-- Create the persisted confirmation-gate table for chat-proposed account actions.
CREATE TABLE IF NOT EXISTS pending_agent_actions (
  token VARCHAR(36) PRIMARY KEY,
  customer_id BIGINT NOT NULL,
  action_type VARCHAR(20) NOT NULL,
  parameters_json TEXT NOT NULL,
  human_summary TEXT NOT NULL,
  status VARCHAR(20) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  expires_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_paa_customer_id ON pending_agent_actions(customer_id);
CREATE INDEX IF NOT EXISTS idx_paa_status ON pending_agent_actions(status);

-- Repair databases where the table was first created with CLOB columns.
ALTER TABLE pending_agent_actions ALTER COLUMN parameters_json TEXT;
ALTER TABLE pending_agent_actions ALTER COLUMN human_summary TEXT;
