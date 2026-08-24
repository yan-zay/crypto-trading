-- Trusted public keys are provisioned out-of-band. The table intentionally starts empty,
-- so a fresh deployment cannot promote an unsigned or self-signed strategy artifact.
CREATE TABLE strategy_signing_key (
    key_id VARCHAR(64) PRIMARY KEY,
    algorithm VARCHAR(20) NOT NULL,
    public_key_x509_base64 TEXT NOT NULL,
    status VARCHAR(20) NOT NULL COMMENT 'ACTIVE/REVOKED',
    valid_from_ms BIGINT NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    provisioned_by VARCHAR(100) NOT NULL,
    provisioned_at_ms BIGINT NOT NULL,
    revoked_by VARCHAR(100) NULL,
    revoked_at_ms BIGINT NULL,
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_strategy_signing_key_status (status, expires_at_ms),
    CONSTRAINT chk_strategy_signing_key_window CHECK (expires_at_ms > valid_from_ms)
) ENGINE=InnoDB COMMENT='Explicit trust roots for signed strategy release artifacts';

-- Artifact identity is exactly SHA-256(content). Signature payload also binds strategy,
-- version, digest, signing timestamp and expiry. No UPDATE/DELETE path exists in the app.
CREATE TABLE strategy_artifact (
    artifact_sha256 CHAR(64) PRIMARY KEY,
    strategy_name VARCHAR(100) NOT NULL,
    artifact_version VARCHAR(100) NOT NULL,
    artifact_content MEDIUMBLOB NOT NULL,
    media_type VARCHAR(160) NOT NULL,
    artifact_uri VARCHAR(1000) NULL,
    signature_algorithm VARCHAR(20) NOT NULL,
    signing_key_id VARCHAR(64) NOT NULL,
    signature_base64 VARCHAR(1024) NOT NULL,
    signed_at_ms BIGINT NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    submitted_by VARCHAR(100) NOT NULL,
    submitted_at_ms BIGINT NOT NULL,
    research_registration_id VARCHAR(64) NULL,
    research_registration_sha256 CHAR(64) NULL,
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_strategy_artifact_version (strategy_name, artifact_version),
    UNIQUE KEY uk_strategy_artifact_exact_identity (artifact_sha256, strategy_name),
    INDEX idx_strategy_artifact_strategy (strategy_name, submitted_at_ms),
    INDEX idx_strategy_artifact_research (research_registration_id, research_registration_sha256),
    CONSTRAINT fk_strategy_artifact_signing_key
        FOREIGN KEY (signing_key_id) REFERENCES strategy_signing_key(key_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_strategy_artifact_expiry CHECK (expires_at_ms > signed_at_ms),
    CONSTRAINT chk_strategy_artifact_research_pair CHECK (
        (research_registration_id IS NULL AND research_registration_sha256 IS NULL)
        OR (research_registration_id IS NOT NULL AND research_registration_sha256 IS NOT NULL))
) ENGINE=InnoDB COMMENT='Immutable signed and content-addressed strategy artifacts';

CREATE TABLE strategy_lifecycle_state (
    strategy_name VARCHAR(100) PRIMARY KEY,
    artifact_sha256 CHAR(64) NOT NULL,
    previous_artifact_sha256 CHAR(64) NULL,
    stage VARCHAR(20) NOT NULL COMMENT 'DRAFT/REVIEW/APPROVED/SHADOW/CANARY/PROMOTED/ROLLED_BACK',
    review_cycle BIGINT NOT NULL DEFAULT 0,
    state_version BIGINT NOT NULL DEFAULT 0,
    updated_by VARCHAR(100) NOT NULL,
    updated_at_ms BIGINT NOT NULL,
    policy_reason VARCHAR(100) NOT NULL,
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_strategy_lifecycle_stage (stage, updated_at_ms),
    CONSTRAINT fk_strategy_lifecycle_artifact
        FOREIGN KEY (artifact_sha256) REFERENCES strategy_artifact(artifact_sha256)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_strategy_lifecycle_previous_artifact
        FOREIGN KEY (previous_artifact_sha256) REFERENCES strategy_artifact(artifact_sha256)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_strategy_lifecycle_stage CHECK (
        stage IN ('DRAFT','REVIEW','APPROVED','SHADOW','CANARY','PROMOTED','ROLLED_BACK'))
) ENGINE=InnoDB COMMENT='CAS cursor for deterministic strategy promotion state';

CREATE TABLE strategy_lifecycle_approval (
    approval_id VARCHAR(64) PRIMARY KEY,
    strategy_name VARCHAR(100) NOT NULL,
    artifact_sha256 CHAR(64) NOT NULL,
    review_cycle BIGINT NOT NULL,
    decision VARCHAR(20) NOT NULL COMMENT 'APPROVE/REJECT',
    approval_scope_sha256 CHAR(64) NOT NULL,
    evidence_set_sha256 CHAR(64) NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    approved_by VARCHAR(100) NOT NULL,
    approved_at_ms BIGINT NOT NULL,
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_strategy_approval_cycle
        (strategy_name, artifact_sha256, review_cycle, approved_at_ms),
    INDEX idx_strategy_approval_expiry (expires_at_ms),
    CONSTRAINT fk_strategy_approval_artifact
        FOREIGN KEY (artifact_sha256) REFERENCES strategy_artifact(artifact_sha256)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_strategy_approval_decision CHECK (decision IN ('APPROVE','REJECT')),
    CONSTRAINT chk_strategy_approval_expiry CHECK (expires_at_ms > approved_at_ms)
) ENGINE=InnoDB COMMENT='Append-only independent approval decisions with TTL and evidence scope';

CREATE TABLE strategy_evidence_snapshot (
    snapshot_id VARCHAR(64) PRIMARY KEY,
    strategy_name VARCHAR(100) NOT NULL,
    artifact_sha256 CHAR(64) NOT NULL,
    evidence_type VARCHAR(40) NOT NULL,
    outcome VARCHAR(20) NOT NULL COMMENT 'PASS/FAIL/UNKNOWN',
    evidence_sha256 CHAR(64) NOT NULL,
    evidence_uri VARCHAR(1000) NOT NULL,
    captured_at_ms BIGINT NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    recorded_by VARCHAR(100) NOT NULL,
    recorded_at_ms BIGINT NOT NULL,
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_strategy_evidence_latest
        (strategy_name, artifact_sha256, evidence_type, recorded_at_ms),
    INDEX idx_strategy_evidence_expiry (expires_at_ms),
    CONSTRAINT fk_strategy_evidence_artifact
        FOREIGN KEY (artifact_sha256) REFERENCES strategy_artifact(artifact_sha256)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_strategy_evidence_outcome CHECK (outcome IN ('PASS','FAIL','UNKNOWN')),
    CONSTRAINT chk_strategy_evidence_expiry CHECK (expires_at_ms > captured_at_ms)
) ENGINE=InnoDB COMMENT='Append-only content-addressed promotion evidence snapshots';

CREATE TABLE strategy_lifecycle_transition (
    event_id VARCHAR(64) PRIMARY KEY,
    strategy_name VARCHAR(100) NOT NULL,
    artifact_sha256 CHAR(64) NULL,
    sequence_no BIGINT NOT NULL,
    review_cycle BIGINT NOT NULL,
    from_stage VARCHAR(20) NULL,
    to_stage VARCHAR(20) NULL,
    action VARCHAR(40) NOT NULL,
    accepted TINYINT(1) NOT NULL,
    reason_code VARCHAR(100) NOT NULL,
    detail_message VARCHAR(2000) NOT NULL,
    operator VARCHAR(100) NOT NULL,
    evidence_set_sha256 CHAR(64) NULL,
    occurred_at_ms BIGINT NOT NULL,
    previous_event_sha256 CHAR(64) NOT NULL,
    event_sha256 CHAR(64) NOT NULL,
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_strategy_lifecycle_sequence (strategy_name, sequence_no),
    UNIQUE KEY uk_strategy_lifecycle_event_hash (event_sha256),
    INDEX idx_strategy_lifecycle_event_time (strategy_name, occurred_at_ms),
    INDEX idx_strategy_lifecycle_event_outcome (accepted, reason_code, occurred_at_ms)
) ENGINE=InnoDB COMMENT='Append-only hash-chained strategy lifecycle and policy audit';

-- Database-level immutability makes approvals/evidence/audit history non-rewritable even if
-- an application mapper is accidentally expanded later. Signing keys remain revocable.
CREATE TRIGGER trg_strategy_artifact_no_update BEFORE UPDATE ON strategy_artifact FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'strategy artifacts are immutable';
CREATE TRIGGER trg_strategy_artifact_no_delete BEFORE DELETE ON strategy_artifact FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'strategy artifacts are immutable';
CREATE TRIGGER trg_strategy_approval_no_update BEFORE UPDATE ON strategy_lifecycle_approval FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'strategy approvals are immutable';
CREATE TRIGGER trg_strategy_approval_no_delete BEFORE DELETE ON strategy_lifecycle_approval FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'strategy approvals are immutable';
CREATE TRIGGER trg_strategy_evidence_no_update BEFORE UPDATE ON strategy_evidence_snapshot FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'strategy evidence snapshots are immutable';
CREATE TRIGGER trg_strategy_evidence_no_delete BEFORE DELETE ON strategy_evidence_snapshot FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'strategy evidence snapshots are immutable';
CREATE TRIGGER trg_strategy_transition_no_update BEFORE UPDATE ON strategy_lifecycle_transition FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'strategy lifecycle transitions are immutable';
CREATE TRIGGER trg_strategy_transition_no_delete BEFORE DELETE ON strategy_lifecycle_transition FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'strategy lifecycle transitions are immutable';
