-- Runtime selection is an immutable, versioned assignment of one signed strategy artifact to
-- one exact governance subject. The mutable head is only a CAS pointer; historical versions are
-- protected by triggers and remain independently auditable through the lifecycle hash chain.
CREATE TABLE strategy_release_assignment_head (
    strategy_name VARCHAR(100) PRIMARY KEY,
    current_assignment_id VARCHAR(64) NULL,
    current_assignment_version BIGINT NOT NULL DEFAULT -1,
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB COMMENT='CAS head for exact runtime strategy release assignment';

CREATE TABLE strategy_release_assignment (
    assignment_id VARCHAR(64) PRIMARY KEY,
    strategy_name VARCHAR(100) NOT NULL,
    artifact_sha256 CHAR(64) NOT NULL,
    minimum_stage VARCHAR(20) NOT NULL COMMENT 'SHADOW/CANARY/PROMOTED',
    governance_scope VARCHAR(200) NOT NULL,
    governance_environment VARCHAR(40) NOT NULL,
    account_scope VARCHAR(200) NOT NULL,
    git_commit VARCHAR(100) NOT NULL,
    build_sha256 CHAR(64) NOT NULL,
    container_image_digest VARCHAR(80) NOT NULL,
    configuration_sha256 CHAR(64) NOT NULL,
    dataset_manifest_sha256 CHAR(64) NOT NULL,
    key_reference VARCHAR(300) NOT NULL,
    run_id VARCHAR(100) NOT NULL,
    policy_version VARCHAR(100) NOT NULL,
    governance_subject_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    assignment_version BIGINT NOT NULL,
    assigned_by VARCHAR(100) NOT NULL,
    assigned_at_ms BIGINT NOT NULL,
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_strategy_release_assignment_version (strategy_name, assignment_version),
    UNIQUE KEY uk_strategy_release_exact_lineage
        (assignment_id, assignment_version, strategy_name,
         artifact_sha256, governance_subject_sha256),
    UNIQUE KEY uk_strategy_release_signal_lineage
        (assignment_id, strategy_name, artifact_sha256, governance_subject_sha256),
    UNIQUE KEY uk_strategy_release_head_target
        (assignment_id, assignment_version, strategy_name),
    UNIQUE KEY uk_strategy_release_assignment_subject
        (strategy_name, artifact_sha256, governance_subject_sha256, assignment_version),
    INDEX idx_strategy_release_assignment_expiry (expires_at_ms),
    CONSTRAINT fk_strategy_release_assignment_artifact
        FOREIGN KEY (artifact_sha256, strategy_name)
        REFERENCES strategy_artifact(artifact_sha256, strategy_name)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_strategy_release_assignment_stage CHECK (
        minimum_stage IN ('SHADOW','CANARY','PROMOTED')),
    CONSTRAINT chk_strategy_release_assignment_expiry CHECK (expires_at_ms > assigned_at_ms),
    CONSTRAINT chk_strategy_release_assignment_version CHECK (assignment_version >= 0)
) ENGINE=InnoDB COMMENT='Immutable exact release/governance assignments';

ALTER TABLE strategy_release_assignment_head
    ADD CONSTRAINT fk_strategy_release_assignment_head_current
        FOREIGN KEY (current_assignment_id, current_assignment_version, strategy_name)
        REFERENCES strategy_release_assignment
            (assignment_id, assignment_version, strategy_name)
        ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE signal_event
    MODIFY COLUMN strategy_name VARCHAR(100) NOT NULL COMMENT '策略名称',
    ADD COLUMN release_assignment_id VARCHAR(64) NULL AFTER strategy_name,
    ADD COLUMN strategy_artifact_sha256 CHAR(64) NULL AFTER release_assignment_id,
    ADD COLUMN governance_subject_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER strategy_artifact_sha256,
    ADD INDEX idx_signal_release (strategy_name, strategy_artifact_sha256, signal_time),
    ADD CONSTRAINT fk_signal_release_lineage
        FOREIGN KEY (release_assignment_id, strategy_name,
                     strategy_artifact_sha256, governance_subject_sha256)
        REFERENCES strategy_release_assignment
            (assignment_id, strategy_name, artifact_sha256, governance_subject_sha256)
        ON DELETE RESTRICT ON UPDATE RESTRICT;

CREATE TRIGGER trg_strategy_release_assignment_no_update
BEFORE UPDATE ON strategy_release_assignment FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'strategy release assignments are immutable';

CREATE TRIGGER trg_strategy_release_assignment_no_delete
BEFORE DELETE ON strategy_release_assignment FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'strategy release assignments are immutable';

DELIMITER $$
CREATE TRIGGER trg_strategy_release_assignment_head_initial
BEFORE INSERT ON strategy_release_assignment_head
FOR EACH ROW
BEGIN
    IF NEW.current_assignment_id IS NOT NULL OR NEW.current_assignment_version<>-1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT='release assignment head must start empty at version -1';
    END IF;
END$$

CREATE TRIGGER trg_strategy_release_assignment_head_forward_only
BEFORE UPDATE ON strategy_release_assignment_head
FOR EACH ROW
BEGIN
    DECLARE target_matches INT DEFAULT 0;
    IF NEW.strategy_name<>OLD.strategy_name
       OR NEW.current_assignment_id IS NULL
       OR NEW.current_assignment_version<>OLD.current_assignment_version+1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT='release assignment head must advance exactly one version';
    END IF;
    SELECT COUNT(*) INTO target_matches
      FROM strategy_release_assignment
     WHERE assignment_id=NEW.current_assignment_id
       AND assignment_version=NEW.current_assignment_version
       AND strategy_name=OLD.strategy_name;
    IF target_matches<>1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT='release assignment head target does not match strategy and version';
    END IF;
END$$

CREATE TRIGGER trg_strategy_release_assignment_head_no_delete
BEFORE DELETE ON strategy_release_assignment_head
FOR EACH ROW SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT='release assignment heads cannot be deleted'$$

CREATE TRIGGER trg_signal_event_require_release_lineage
BEFORE INSERT ON signal_event
FOR EACH ROW
BEGIN
    IF NEW.release_assignment_id IS NULL
       OR NEW.strategy_artifact_sha256 IS NULL
       OR NEW.governance_subject_sha256 IS NULL THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT='new signal events require complete release lineage';
    END IF;
END$$
DELIMITER ;
