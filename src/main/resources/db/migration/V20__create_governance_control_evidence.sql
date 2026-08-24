-- Fail-closed index of externally retained legal/security/operations evidence.
-- A VERIFIED row is never proof by itself: the policy gate must retrieve, hash and verify the
-- signed artifact through a separately configured trust adapter on every decision.
CREATE TABLE governance_control_evidence (
    evidence_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    control_kind VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    environment VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subject_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    policy_version VARCHAR(96) COLLATE utf8mb4_bin NOT NULL,
    evidence_status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner VARCHAR(96) COLLATE utf8mb4_bin NOT NULL,
    issuer VARCHAR(128) COLLATE utf8mb4_bin NOT NULL DEFAULT '',
    started_at_ms BIGINT NOT NULL DEFAULT 0,
    completed_at_ms BIGINT NOT NULL DEFAULT 0,
    expires_at_ms BIGINT NOT NULL DEFAULT 0,
    registered_at_ms BIGINT NOT NULL,
    artifact_uri VARCHAR(512) COLLATE utf8mb4_bin NOT NULL DEFAULT '',
    artifact_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '',
    predecessor_evidence_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NULL,
    predecessor_slot VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (COALESCE(predecessor_evidence_id, '#ROOT#')) STORED,
    notes VARCHAR(1000) COLLATE utf8mb4_bin NOT NULL DEFAULT '',
    create_time TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_governance_evidence_kind CHECK (control_kind IN
        ('LEGAL_APPROVAL','KMS_KEY_CUSTODY','MFA','DUAL_APPROVAL','IP_ALLOWLIST',
         'EXTERNAL_ALERT_DELIVERY','BACKUP_PITR_RESTORE','RPO_RTO','VENUE_DEMO_CONTRACT',
         'PRIVATE_STREAM_STABILITY','SOAK_24H','SOAK_7D','CHAOS','FORWARD_PAPER_30D')),
    CONSTRAINT chk_governance_evidence_status
        CHECK (evidence_status IN ('NOT_VERIFIED','VERIFIED','FAILED','REVOKED')),
    CONSTRAINT chk_governance_evidence_identity
        CHECK (CHAR_LENGTH(TRIM(evidence_id))>0 AND CHAR_LENGTH(TRIM(scope))>0
               AND CHAR_LENGTH(TRIM(environment))>0 AND environment=UPPER(environment)
               AND CHAR_LENGTH(TRIM(policy_version))>0 AND CHAR_LENGTH(TRIM(owner))>0),
    CONSTRAINT chk_governance_evidence_subject_hash
        CHECK (subject_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_governance_evidence_artifact_hash
        CHECK (artifact_sha256='' OR artifact_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_governance_evidence_timestamps
        CHECK (started_at_ms>=0 AND completed_at_ms>=0 AND expires_at_ms>=0
               AND registered_at_ms>0
               AND (completed_at_ms=0 OR
                    (started_at_ms>0 AND completed_at_ms>=started_at_ms
                     AND registered_at_ms>=completed_at_ms))
               AND (expires_at_ms=0 OR
                    (completed_at_ms>0 AND expires_at_ms>completed_at_ms))),
    CONSTRAINT chk_governance_verified_fields
        CHECK (evidence_status<>'VERIFIED' OR
               (completed_at_ms>0 AND expires_at_ms>completed_at_ms
                AND issuer<>'' AND artifact_uri LIKE 'evidence://%/%'
                AND LOCATE('@',artifact_uri)=0 AND LOCATE('?',artifact_uri)=0
                AND LOCATE('#',artifact_uri)=0
                AND artifact_sha256 REGEXP '^[0-9a-f]{64}$')),
    CONSTRAINT chk_governance_revoked_fields
        CHECK (evidence_status<>'REVOKED' OR
               (predecessor_evidence_id IS NOT NULL AND completed_at_ms>0
                AND issuer<>'' AND artifact_uri LIKE 'evidence://%/%'
                AND LOCATE('@',artifact_uri)=0 AND LOCATE('?',artifact_uri)=0
                AND LOCATE('#',artifact_uri)=0
                AND artifact_sha256 REGEXP '^[0-9a-f]{64}$')),
    CONSTRAINT chk_governance_predecessor_not_self
        CHECK (predecessor_evidence_id IS NULL OR predecessor_evidence_id<>evidence_id),
    UNIQUE KEY uk_governance_evidence_stream_ref
        (control_kind,scope,environment,subject_sha256,policy_version,evidence_id),
    UNIQUE KEY uk_governance_evidence_single_successor
        (control_kind,scope,environment,subject_sha256,policy_version,predecessor_slot),
    INDEX idx_governance_evidence_history
        (control_kind,scope,environment,subject_sha256,policy_version,registered_at_ms,evidence_id),
    CONSTRAINT fk_governance_evidence_stream_predecessor
        FOREIGN KEY (control_kind,scope,environment,subject_sha256,policy_version,
                     predecessor_evidence_id)
        REFERENCES governance_control_evidence
            (control_kind,scope,environment,subject_sha256,policy_version,evidence_id)
) ENGINE=InnoDB COMMENT='Immutable references to externally verified control evidence';

CREATE TABLE governance_control_evidence_head (
    control_kind VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    environment VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subject_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    policy_version VARCHAR(96) COLLATE utf8mb4_bin NOT NULL,
    latest_evidence_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NULL,
    head_version BIGINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_time TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (control_kind,scope,environment,subject_sha256,policy_version),
    CONSTRAINT chk_governance_evidence_head_subject_hash
        CHECK (subject_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_governance_evidence_head_shape
        CHECK ((head_version=0 AND latest_evidence_id IS NULL)
               OR (head_version>0 AND latest_evidence_id IS NOT NULL)),
    CONSTRAINT fk_governance_evidence_head_latest
        FOREIGN KEY (control_kind,scope,environment,subject_sha256,policy_version,
                     latest_evidence_id)
        REFERENCES governance_control_evidence
            (control_kind,scope,environment,subject_sha256,policy_version,evidence_id)
) ENGINE=InnoDB COMMENT='Serialized compare-and-set head for each evidence stream';

CREATE TABLE governance_approval_consumption (
    request_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    approval_action VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    resource_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    governance_subject_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requested_by VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    approved_by VARCHAR(512) COLLATE utf8mb4_bin NOT NULL,
    request_created_at_ms BIGINT NOT NULL,
    request_expires_at_ms BIGINT NOT NULL,
    consumed_at_ms BIGINT NOT NULL,
    create_time TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_governance_approval_exact
        (request_id, approval_action, resource_sha256, governance_subject_sha256),
    CONSTRAINT chk_governance_approval_action CHECK (approval_action IN
        ('STRATEGY_CANARY','STRATEGY_PROMOTE','LIVE_CANARY_ENABLE','LIVE_RISK_INCREASE',
         'CAPABILITY_TOKEN_ISSUE','KEY_ROTATION','EVIDENCE_ACCEPTANCE')),
    CONSTRAINT chk_governance_approval_hashes
        CHECK (resource_sha256 REGEXP '^[0-9a-f]{64}$'
               AND governance_subject_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_governance_approval_identity
        CHECK (CHAR_LENGTH(TRIM(request_id))>0 AND CHAR_LENGTH(TRIM(requested_by))>0
               AND CHAR_LENGTH(TRIM(approved_by))>2),
    CONSTRAINT chk_governance_approval_timestamps
        CHECK (request_created_at_ms>0 AND request_expires_at_ms>request_created_at_ms
               AND consumed_at_ms>=request_created_at_ms
               AND consumed_at_ms<request_expires_at_ms
               AND request_expires_at_ms-request_created_at_ms<=900000)
) ENGINE=InnoDB COMMENT='One-time exact dual-control action consumption ledger';

CREATE TABLE governance_approval_mfa_reservation (
    replay_reservation_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    request_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    approver VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    reservation_ordinal SMALLINT UNSIGNED NOT NULL,
    create_time TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (replay_reservation_id),
    UNIQUE KEY uk_governance_approval_mfa_request_approver (request_id,approver),
    UNIQUE KEY uk_governance_approval_mfa_request_ordinal (request_id,reservation_ordinal),
    CONSTRAINT chk_governance_approval_mfa_identity
        CHECK (CHAR_LENGTH(TRIM(replay_reservation_id))>0
               AND CHAR_LENGTH(TRIM(approver))>0),
    CONSTRAINT fk_governance_approval_mfa_request
        FOREIGN KEY (request_id) REFERENCES governance_approval_consumption(request_id)
) ENGINE=InnoDB COMMENT='Globally unique MFA replay reservations bound one-to-one to approvers';

DELIMITER $$
CREATE TRIGGER trg_governance_control_evidence_head_initial
BEFORE INSERT ON governance_control_evidence_head
FOR EACH ROW
BEGIN
    IF NEW.head_version<>0 OR NEW.latest_evidence_id IS NOT NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='evidence head must start empty at version zero';
    END IF;
END$$

CREATE TRIGGER trg_governance_control_evidence_head_forward_only
BEFORE UPDATE ON governance_control_evidence_head
FOR EACH ROW
BEGIN
    DECLARE new_predecessor VARCHAR(96);
    IF NEW.control_kind<>OLD.control_kind OR NEW.scope<>OLD.scope
       OR NEW.environment<>OLD.environment OR NEW.subject_sha256<>OLD.subject_sha256
       OR NEW.policy_version<>OLD.policy_version THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='evidence head stream identity is immutable';
    END IF;
    IF NEW.head_version<>OLD.head_version+1 OR NEW.latest_evidence_id IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='evidence head version must advance exactly once';
    END IF;
    SELECT predecessor_evidence_id INTO new_predecessor
      FROM governance_control_evidence
     WHERE control_kind=OLD.control_kind AND scope=OLD.scope
       AND environment=OLD.environment AND subject_sha256=OLD.subject_sha256
       AND policy_version=OLD.policy_version AND evidence_id=NEW.latest_evidence_id;
    IF NOT (new_predecessor <=> OLD.latest_evidence_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='evidence head cannot skip, fork or roll back';
    END IF;
END$$

CREATE TRIGGER trg_governance_control_evidence_no_update
BEFORE UPDATE ON governance_control_evidence
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='governance control evidence is immutable'$$

CREATE TRIGGER trg_governance_control_evidence_no_delete
BEFORE DELETE ON governance_control_evidence
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='governance control evidence is immutable'$$

CREATE TRIGGER trg_governance_control_evidence_head_no_delete
BEFORE DELETE ON governance_control_evidence_head
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='governance control evidence head cannot be deleted'$$

CREATE TRIGGER trg_governance_approval_consumption_no_update
BEFORE UPDATE ON governance_approval_consumption
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='governance approval consumption is immutable'$$

CREATE TRIGGER trg_governance_approval_consumption_no_delete
BEFORE DELETE ON governance_approval_consumption
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='governance approval consumption is immutable'$$

CREATE TRIGGER trg_governance_approval_mfa_reservation_no_update
BEFORE UPDATE ON governance_approval_mfa_reservation
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='governance MFA replay reservation is immutable'$$

CREATE TRIGGER trg_governance_approval_mfa_reservation_no_delete
BEFORE DELETE ON governance_approval_mfa_reservation
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='governance MFA replay reservation is immutable'$$
DELIMITER ;
