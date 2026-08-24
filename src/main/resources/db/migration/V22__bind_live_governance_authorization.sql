-- One exact, short-lived authorization fact is appended before a LIVE_CANARY command can be
-- placed. It is written in the same transaction as the initial OMS fact and risk reservation.
CREATE TABLE live_governance_decision (
    decision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    client_order_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    command_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    exchange VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    strategy_id VARCHAR(100) NOT NULL,
    release_assignment_id VARCHAR(64) NOT NULL,
    release_assignment_version BIGINT NOT NULL,
    strategy_artifact_sha256 CHAR(64) NOT NULL,
    governance_subject_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    governance_scope VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    governance_environment VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_scope VARCHAR(200) COLLATE utf8mb4_bin NOT NULL,
    git_commit VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    build_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    container_image_digest VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    configuration_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    dataset_manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    key_reference VARCHAR(300) COLLATE utf8mb4_bin NOT NULL,
    run_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    policy_version VARCHAR(96) COLLATE utf8mb4_bin NOT NULL,
    evidence_set_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_references TEXT COLLATE utf8mb4_bin NOT NULL,
    approval_request_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    approval_action VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    approval_governance_subject_sha256 CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requested_by VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    authorized_at_ms BIGINT NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    create_time TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_live_governance_client (client_order_id),
    UNIQUE KEY uk_live_governance_command (command_sha256),
    UNIQUE KEY uk_live_governance_approval (approval_request_id),
    UNIQUE KEY uk_live_governance_exact_evidence_context
        (decision_id, governance_scope, governance_environment,
         governance_subject_sha256, policy_version),
    CONSTRAINT fk_live_governance_release
        FOREIGN KEY (release_assignment_id, release_assignment_version, strategy_id,
                     strategy_artifact_sha256, governance_subject_sha256)
        REFERENCES strategy_release_assignment
            (assignment_id, assignment_version, strategy_name,
             artifact_sha256, governance_subject_sha256),
    CONSTRAINT fk_live_governance_approval
        FOREIGN KEY (approval_request_id, approval_action,
                     command_sha256, approval_governance_subject_sha256)
        REFERENCES governance_approval_consumption
            (request_id, approval_action, resource_sha256, governance_subject_sha256),
    CONSTRAINT chk_live_governance_hashes CHECK (
        command_sha256 REGEXP '^[0-9a-f]{64}$'
        AND strategy_artifact_sha256 REGEXP '^[0-9a-f]{64}$'
        AND governance_subject_sha256 REGEXP '^[0-9a-f]{64}$'
        AND build_sha256 REGEXP '^[0-9a-f]{64}$'
        AND configuration_sha256 REGEXP '^[0-9a-f]{64}$'
        AND dataset_manifest_sha256 REGEXP '^[0-9a-f]{64}$'
        AND evidence_set_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_live_governance_identity CHECK (
        CHAR_LENGTH(TRIM(decision_id))>0 AND CHAR_LENGTH(TRIM(account_id))>0
        AND CHAR_LENGTH(TRIM(strategy_id))>0 AND release_assignment_version>=0
        AND approval_action='LIVE_RISK_INCREASE'
        AND approval_governance_subject_sha256=governance_subject_sha256
        AND CHAR_LENGTH(TRIM(account_scope))>0 AND CHAR_LENGTH(TRIM(key_reference))>0
        AND CHAR_LENGTH(TRIM(run_id))>0 AND CHAR_LENGTH(TRIM(policy_version))>0
        AND CHAR_LENGTH(TRIM(requested_by))>0),
    CONSTRAINT chk_live_governance_window CHECK (
        authorized_at_ms>0 AND expires_at_ms>authorized_at_ms
        AND expires_at_ms-authorized_at_ms<=30000)
) ENGINE=InnoDB COMMENT='Append-only exact LIVE_CANARY risk-increase decisions';

ALTER TABLE governance_control_evidence
    ADD UNIQUE KEY uk_governance_evidence_exact_verified_binding
        (control_kind, scope, environment, subject_sha256, policy_version,
         evidence_id, evidence_status);

CREATE TABLE live_governance_decision_evidence (
    decision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    control_kind VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    governance_scope VARCHAR(128) COLLATE utf8mb4_bin NOT NULL DEFAULT '',
    governance_environment VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT '',
    governance_subject_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT '',
    policy_version VARCHAR(96) COLLATE utf8mb4_bin NOT NULL DEFAULT '',
    evidence_status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'VERIFIED',
    create_time TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (decision_id, control_kind),
    UNIQUE KEY uk_live_governance_decision_evidence (decision_id, evidence_id),
    CONSTRAINT fk_live_governance_evidence_exact_decision
        FOREIGN KEY (decision_id, governance_scope, governance_environment,
                     governance_subject_sha256, policy_version)
        REFERENCES live_governance_decision
            (decision_id, governance_scope, governance_environment,
             governance_subject_sha256, policy_version),
    CONSTRAINT fk_live_governance_evidence_exact_artifact
        FOREIGN KEY (control_kind, governance_scope, governance_environment,
                     governance_subject_sha256, policy_version, evidence_id,
                     evidence_status)
        REFERENCES governance_control_evidence
            (control_kind, scope, environment, subject_sha256, policy_version,
             evidence_id, evidence_status),
    CONSTRAINT chk_live_governance_evidence_exact CHECK (
        governance_subject_sha256 REGEXP '^[0-9a-f]{64}$'
        AND evidence_status='VERIFIED')
) ENGINE=InnoDB COMMENT='Exact external evidence accepted by a live governance decision';

ALTER TABLE oms_order
    ADD COLUMN command_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER last_event_at_ms,
    ADD COLUMN authorization_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER command_sha256,
    ADD COLUMN governance_decision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER authorization_kind,
    ADD COLUMN governance_subject_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER governance_decision_id,
    ADD COLUMN strategy_artifact_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER governance_subject_sha256,
    ADD UNIQUE KEY uk_oms_governance_decision (governance_decision_id),
    ADD INDEX idx_oms_command_sha256 (command_sha256),
    ADD CONSTRAINT fk_oms_governance_decision
        FOREIGN KEY (governance_decision_id) REFERENCES live_governance_decision(decision_id),
    ADD CONSTRAINT chk_oms_live_authorization CHECK (
        (command_sha256 IS NULL AND authorization_kind IS NULL
         AND governance_decision_id IS NULL AND governance_subject_sha256 IS NULL
         AND strategy_artifact_sha256 IS NULL)
        OR
        (command_sha256 REGEXP '^[0-9a-f]{64}$'
         AND authorization_kind IN
             ('SANDBOX_RISK_INCREASE','LIVE_RISK_INCREASE','RISK_REDUCTION')
         AND ((authorization_kind='LIVE_RISK_INCREASE'
               AND governance_decision_id IS NOT NULL
               AND governance_subject_sha256 REGEXP '^[0-9a-f]{64}$'
               AND strategy_artifact_sha256 REGEXP '^[0-9a-f]{64}$')
              OR
              (authorization_kind<>'LIVE_RISK_INCREASE'
               AND governance_decision_id IS NULL
               AND governance_subject_sha256 IS NULL
               AND strategy_artifact_sha256 IS NULL))));

DELIMITER $$
CREATE TRIGGER trg_live_governance_evidence_bind_exact
BEFORE INSERT ON live_governance_decision_evidence
FOR EACH ROW
BEGIN
    DECLARE exact_scope VARCHAR(128) DEFAULT NULL;
    DECLARE exact_environment VARCHAR(32) DEFAULT NULL;
    DECLARE exact_subject_sha256 CHAR(64) DEFAULT NULL;
    DECLARE exact_policy_version VARCHAR(96) DEFAULT NULL;
    SELECT MAX(d.governance_scope), MAX(d.governance_environment),
           MAX(d.governance_subject_sha256), MAX(d.policy_version)
      INTO exact_scope, exact_environment, exact_subject_sha256, exact_policy_version
      FROM live_governance_decision d
      JOIN governance_control_evidence e
        ON e.evidence_id=NEW.evidence_id
       AND e.control_kind=NEW.control_kind
       AND e.scope=d.governance_scope
       AND e.environment=d.governance_environment
       AND e.subject_sha256=d.governance_subject_sha256
       AND e.policy_version=d.policy_version
       AND e.evidence_status='VERIFIED'
     WHERE d.decision_id=NEW.decision_id;
    IF exact_scope IS NULL THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT='live governance evidence requires an existing decision';
    END IF;
    SET NEW.governance_scope=exact_scope;
    SET NEW.governance_environment=exact_environment;
    SET NEW.governance_subject_sha256=exact_subject_sha256;
    SET NEW.policy_version=exact_policy_version;
    SET NEW.evidence_status='VERIFIED';
END$$

CREATE TRIGGER trg_live_governance_decision_no_update
BEFORE UPDATE ON live_governance_decision
FOR EACH ROW SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT='live governance decisions are immutable'$$

CREATE TRIGGER trg_live_governance_decision_no_delete
BEFORE DELETE ON live_governance_decision
FOR EACH ROW SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT='live governance decisions are immutable'$$

CREATE TRIGGER trg_live_governance_evidence_no_update
BEFORE UPDATE ON live_governance_decision_evidence
FOR EACH ROW SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT='live governance evidence links are immutable'$$

CREATE TRIGGER trg_live_governance_evidence_no_delete
BEFORE DELETE ON live_governance_decision_evidence
FOR EACH ROW SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT='live governance evidence links are immutable'$$
DELIMITER ;
