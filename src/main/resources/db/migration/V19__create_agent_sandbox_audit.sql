CREATE TABLE agent_tool_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL,
    call_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    agent_level VARCHAR(8) NOT NULL,
    operation VARCHAR(80) NOT NULL,
    outcome VARCHAR(20) NOT NULL,
    operator VARCHAR(100) NOT NULL,
    policy_version VARCHAR(100) NOT NULL,
    input_sha256 CHAR(64) NULL,
    approval_reference VARCHAR(100) NULL,
    model_version VARCHAR(100) NULL,
    prompt_version VARCHAR(100) NULL,
    occurred_at_ms BIGINT NOT NULL,
    elapsed_ms BIGINT NULL,
    reason_code VARCHAR(100) NULL,
    result_reference VARCHAR(100) NULL,
    detail VARCHAR(1000) NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_audit_event (event_id),
    INDEX idx_agent_audit_call (call_id, id),
    INDEX idx_agent_audit_session (session_id, occurred_at_ms),
    INDEX idx_agent_audit_outcome (outcome, occurred_at_ms),
    CONSTRAINT chk_agent_audit_level CHECK (agent_level IN ('L1', 'L2')),
    CONSTRAINT chk_agent_audit_outcome CHECK (
        outcome IN ('STARTED', 'SUCCEEDED', 'DENIED', 'TIMED_OUT', 'FAILED', 'REVOKED'))
) ENGINE=InnoDB COMMENT='Agent L1/L2 追加式工具调用审计';

CREATE TRIGGER trg_agent_tool_audit_no_update
BEFORE UPDATE ON agent_tool_audit
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'agent tool audit is immutable';

CREATE TRIGGER trg_agent_tool_audit_no_delete
BEFORE DELETE ON agent_tool_audit
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'agent tool audit is immutable';

CREATE TABLE agent_paper_shadow_proposal (
    proposal_id VARCHAR(73) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    target_environment VARCHAR(16) NOT NULL,
    approval_reference VARCHAR(100) NOT NULL,
    approval_artifact_sha256 CHAR(64) NOT NULL,
    approval_verification_receipt_sha256 CHAR(64) NOT NULL,
    policy_version VARCHAR(100) NOT NULL,
    created_at_ms BIGINT NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    approved_payload_sha256 CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    proposal_json JSON NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_agent_proposal_session (session_id, created_at_ms),
    INDEX idx_agent_proposal_expiry (status, expires_at_ms),
    UNIQUE KEY uk_agent_proposal_approval (approval_reference),
    UNIQUE KEY uk_agent_proposal_approval_artifact (approval_artifact_sha256),
    CONSTRAINT chk_agent_proposal_target CHECK (target_environment IN ('PAPER', 'SHADOW')),
    CONSTRAINT chk_agent_proposal_status CHECK (status = 'SUBMITTED'),
    CONSTRAINT chk_agent_proposal_ttl CHECK (expires_at_ms > created_at_ms)
) ENGINE=InnoDB COMMENT='Agent L2 短期 PAPER/SHADOW 提案；不具备执行能力';

CREATE TRIGGER trg_agent_proposal_no_update
BEFORE UPDATE ON agent_paper_shadow_proposal
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'agent proposal and approval bindings are immutable';

CREATE TRIGGER trg_agent_proposal_no_delete
BEFORE DELETE ON agent_paper_shadow_proposal
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'agent proposal and approval bindings are immutable';

CREATE TABLE agent_paper_shadow_proposal_revocation (
    proposal_id VARCHAR(73) PRIMARY KEY,
    revoked_by VARCHAR(100) NOT NULL,
    revoke_reason VARCHAR(500) NOT NULL,
    revoked_at_ms BIGINT NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_proposal_revocation_proposal
        FOREIGN KEY (proposal_id) REFERENCES agent_paper_shadow_proposal(proposal_id)
) ENGINE=InnoDB COMMENT='Agent L2 提案追加式撤销证据';

CREATE TRIGGER trg_agent_proposal_revocation_no_update
BEFORE UPDATE ON agent_paper_shadow_proposal_revocation
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'agent proposal revocation is immutable';

CREATE TRIGGER trg_agent_proposal_revocation_no_delete
BEFORE DELETE ON agent_paper_shadow_proposal_revocation
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'agent proposal revocation is immutable';
