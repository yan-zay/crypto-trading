-- Append-only research experiment registry. A family is frozen before execution;
-- lifecycle changes and outputs are represented only by hash-chained events.
CREATE TABLE research_experiment_registration (
    registration_id VARCHAR(64) PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL,
    registration_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    experiment_name VARCHAR(160) NOT NULL,
    hypothesis VARCHAR(2000) NOT NULL,
    protocol_version VARCHAR(100) NOT NULL,
    model_version VARCHAR(160) NOT NULL,
    prompt_version VARCHAR(160) NOT NULL,
    declared_trial_count INT NOT NULL,
    registered_by VARCHAR(100) NOT NULL,
    registered_at_ms BIGINT NOT NULL,
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_research_experiment_idempotency (idempotency_key),
    UNIQUE KEY uk_research_experiment_digest (registration_sha256),
    INDEX idx_research_experiment_registered_at (registered_at_ms),
    CONSTRAINT chk_research_experiment_trial_count CHECK (declared_trial_count > 0),
    CONSTRAINT chk_research_experiment_registration_sha
        CHECK (registration_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_research_experiment_registered_at CHECK (registered_at_ms > 0)
) ENGINE=InnoDB COMMENT='Immutable pre-registration of a complete research experiment family';

-- The pointer is the single compare-and-swap serialization point for the event
-- stream. Event rows remain append-only; a failed CAS rolls the append back.
CREATE TABLE research_experiment_chain_head (
    registration_id VARCHAR(64) PRIMARY KEY,
    last_event_sequence BIGINT NOT NULL,
    last_event_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version BIGINT NOT NULL,
    update_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_research_experiment_chain_registration
        FOREIGN KEY (registration_id) REFERENCES research_experiment_registration(registration_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_research_experiment_chain_sequence CHECK (last_event_sequence >= -1),
    CONSTRAINT chk_research_experiment_chain_version CHECK (version >= 0),
    CONSTRAINT chk_research_experiment_chain_sha
        CHECK (last_event_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_research_experiment_chain_genesis
        CHECK ((last_event_sequence=-1 AND version=0
                    AND last_event_sha256='0000000000000000000000000000000000000000000000000000000000000000')
               OR (last_event_sequence>=0 AND version=last_event_sequence+1))
) ENGINE=InnoDB COMMENT='CAS head for the immutable research experiment event chain';

CREATE TABLE research_experiment_artifact (
    registration_id VARCHAR(64) NOT NULL,
    artifact_id VARCHAR(100) NOT NULL,
    artifact_kind VARCHAR(30) NOT NULL,
    artifact_version VARCHAR(160) NOT NULL,
    artifact_uri VARCHAR(1000) NOT NULL,
    artifact_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    media_type VARCHAR(160) NOT NULL,
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (registration_id, artifact_id),
    UNIQUE KEY uk_research_experiment_artifact_kind
        (registration_id, artifact_kind, artifact_id),
    INDEX idx_research_experiment_artifact_digest (artifact_sha256),
    CONSTRAINT fk_research_experiment_artifact_registration
        FOREIGN KEY (registration_id) REFERENCES research_experiment_registration(registration_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_research_experiment_artifact_kind
        CHECK (artifact_kind IN ('DATASET','CODE','CONTAINER','CONFIG',
                                 'DEPENDENCY_LOCK','MODEL','PROMPT','OTHER')),
    CONSTRAINT chk_research_experiment_artifact_sha
        CHECK (artifact_sha256 REGEXP '^[0-9a-f]{64}$')
) ENGINE=InnoDB COMMENT='Immutable SHA-256 references for experiment inputs and executable artifacts';

CREATE TABLE research_experiment_trial (
    registration_id VARCHAR(64) NOT NULL,
    trial_id VARCHAR(100) NOT NULL,
    trial_ordinal INT NOT NULL,
    hypothesis VARCHAR(2000) NOT NULL,
    random_seed BIGINT NOT NULL,
    parameter_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (registration_id, trial_id),
    UNIQUE KEY uk_research_experiment_trial_ordinal (registration_id, trial_ordinal),
    CONSTRAINT fk_research_experiment_trial_registration
        FOREIGN KEY (registration_id) REFERENCES research_experiment_registration(registration_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_research_experiment_trial_ordinal CHECK (trial_ordinal >= 0),
    CONSTRAINT chk_research_experiment_parameter_sha
        CHECK (parameter_sha256 REGEXP '^[0-9a-f]{64}$')
) ENGINE=InnoDB COMMENT='Complete immutable family of trials declared before any execution';

CREATE TABLE research_experiment_event (
    event_id VARCHAR(64) PRIMARY KEY,
    registration_id VARCHAR(64) NOT NULL,
    trial_id VARCHAR(100) NULL,
    command_idempotency_key VARCHAR(128) NOT NULL,
    command_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_sequence BIGINT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    from_status VARCHAR(30) NULL,
    to_status VARCHAR(30) NOT NULL,
    reason_code VARCHAR(100) NULL,
    detail_message VARCHAR(2000) NULL,
    observed_provenance_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    result_artifact_uri VARCHAR(1000) NULL,
    result_artifact_version VARCHAR(160) NULL,
    result_artifact_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    recorded_by VARCHAR(100) NOT NULL,
    occurred_at_ms BIGINT NOT NULL,
    previous_event_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_research_experiment_event_sequence (registration_id, event_sequence),
    UNIQUE KEY uk_research_experiment_event_command
        (registration_id, command_idempotency_key),
    UNIQUE KEY uk_research_experiment_event_digest (event_sha256),
    INDEX idx_research_experiment_trial_event
        (registration_id, trial_id, event_sequence),
    INDEX idx_research_experiment_event_type
        (registration_id, event_type),
    INDEX idx_research_experiment_event_time (occurred_at_ms),
    CONSTRAINT fk_research_experiment_event_registration
        FOREIGN KEY (registration_id) REFERENCES research_experiment_registration(registration_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_research_experiment_event_trial
        FOREIGN KEY (registration_id, trial_id)
        REFERENCES research_experiment_trial(registration_id, trial_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_research_experiment_event_sequence CHECK (event_sequence >= 0),
    CONSTRAINT chk_research_experiment_event_occurred_at CHECK (occurred_at_ms > 0),
    CONSTRAINT chk_research_experiment_command_sha
        CHECK (command_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_research_experiment_event_type
        CHECK (event_type IN ('FAMILY_REGISTERED','TRIAL_REGISTERED','TRIAL_STARTED',
                              'TRIAL_SUCCEEDED','TRIAL_FAILED','TRIAL_CANCELLED')),
    CONSTRAINT chk_research_experiment_event_state
        CHECK (to_status IN ('REGISTERED','RUNNING','SUCCEEDED','FAILED','CANCELLED')
               AND (from_status IS NULL OR from_status IN
                    ('REGISTERED','RUNNING','SUCCEEDED','FAILED','CANCELLED'))),
    CONSTRAINT chk_research_experiment_event_shape
        CHECK ((event_type='FAMILY_REGISTERED' AND trial_id IS NULL
                    AND from_status IS NULL AND to_status='REGISTERED'
                    AND reason_code IS NULL AND observed_provenance_sha256 IS NULL
                    AND result_artifact_sha256 IS NULL)
               OR (event_type='TRIAL_REGISTERED' AND trial_id IS NOT NULL
                    AND from_status IS NULL AND to_status='REGISTERED'
                    AND reason_code IS NULL AND observed_provenance_sha256 IS NULL
                    AND result_artifact_sha256 IS NULL)
               OR (event_type='TRIAL_STARTED' AND trial_id IS NOT NULL
                    AND from_status='REGISTERED' AND to_status='RUNNING'
                    AND reason_code IS NULL AND observed_provenance_sha256 IS NOT NULL
                    AND result_artifact_sha256 IS NULL)
               OR (event_type='TRIAL_SUCCEEDED' AND trial_id IS NOT NULL
                    AND from_status='RUNNING' AND to_status='SUCCEEDED'
                    AND reason_code IS NULL AND observed_provenance_sha256 IS NULL
                    AND result_artifact_sha256 IS NOT NULL)
               OR (event_type='TRIAL_FAILED' AND trial_id IS NOT NULL
                    AND from_status IN ('REGISTERED','RUNNING') AND to_status='FAILED'
                    AND reason_code IS NOT NULL AND observed_provenance_sha256 IS NULL)
               OR (event_type='TRIAL_CANCELLED' AND trial_id IS NOT NULL
                    AND from_status IN ('REGISTERED','RUNNING') AND to_status='CANCELLED'
                    AND reason_code IS NOT NULL AND observed_provenance_sha256 IS NULL)),
    CONSTRAINT chk_research_experiment_previous_sha
        CHECK (previous_event_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_research_experiment_event_sha
        CHECK (event_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_research_experiment_provenance_sha
        CHECK (observed_provenance_sha256 IS NULL
               OR observed_provenance_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_research_experiment_result_artifact
        CHECK ((result_artifact_uri IS NULL AND result_artifact_version IS NULL
                    AND result_artifact_sha256 IS NULL)
               OR (result_artifact_uri IS NOT NULL AND result_artifact_version IS NOT NULL
                    AND result_artifact_sha256 REGEXP '^[0-9a-f]{64}$'))
) ENGINE=InnoDB COMMENT='Append-only hash-chained experiment lifecycle and outcome audit events';

-- Database-level immutability: applications may append registrations, artifacts,
-- trials and events, but no principal can rewrite or erase the recorded evidence.
CREATE TRIGGER trg_research_experiment_registration_no_update
BEFORE UPDATE ON research_experiment_registration
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'research experiment registrations are immutable';

CREATE TRIGGER trg_research_experiment_registration_no_delete
BEFORE DELETE ON research_experiment_registration
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'research experiment registrations are immutable';

CREATE TRIGGER trg_research_experiment_chain_no_delete
BEFORE DELETE ON research_experiment_chain_head
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'research experiment chain heads cannot be deleted';

-- Definition inserts and the sealing event serialize on the registration row.
-- If a definition wins the lock it commits before sealing; if sealing wins, the
-- waiting definition observes FAMILY_REGISTERED and is rejected after the lock.
DELIMITER $$
CREATE TRIGGER trg_research_experiment_artifact_reject_after_seal
BEFORE INSERT ON research_experiment_artifact
FOR EACH ROW
BEGIN
    DECLARE locked_registration_id VARCHAR(64);
    DECLARE sealed_event_id VARCHAR(64) DEFAULT NULL;
    SELECT registration_id INTO locked_registration_id
      FROM research_experiment_registration
     WHERE registration_id = NEW.registration_id
     FOR UPDATE;
    BEGIN
        DECLARE CONTINUE HANDLER FOR NOT FOUND SET sealed_event_id = NULL;
        SELECT event_id INTO sealed_event_id
          FROM research_experiment_event
         WHERE registration_id = NEW.registration_id
           AND event_type = 'FAMILY_REGISTERED'
         LIMIT 1
         FOR UPDATE;
    END;
    IF sealed_event_id IS NOT NULL THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'research experiment artifact definitions are sealed';
    END IF;
END$$

CREATE TRIGGER trg_research_experiment_trial_reject_after_seal
BEFORE INSERT ON research_experiment_trial
FOR EACH ROW
BEGIN
    DECLARE locked_registration_id VARCHAR(64);
    DECLARE sealed_event_id VARCHAR(64) DEFAULT NULL;
    SELECT registration_id INTO locked_registration_id
      FROM research_experiment_registration
     WHERE registration_id = NEW.registration_id
     FOR UPDATE;
    BEGIN
        DECLARE CONTINUE HANDLER FOR NOT FOUND SET sealed_event_id = NULL;
        SELECT event_id INTO sealed_event_id
          FROM research_experiment_event
         WHERE registration_id = NEW.registration_id
           AND event_type = 'FAMILY_REGISTERED'
         LIMIT 1
         FOR UPDATE;
    END;
    IF sealed_event_id IS NOT NULL THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'research experiment trial definitions are sealed';
    END IF;
END$$

CREATE TRIGGER trg_research_experiment_event_serialize_definition_seal
BEFORE INSERT ON research_experiment_event
FOR EACH ROW
BEGIN
    DECLARE locked_registration_id VARCHAR(64);
    SELECT registration_id INTO locked_registration_id
      FROM research_experiment_registration
     WHERE registration_id = NEW.registration_id
     FOR UPDATE;
END$$

CREATE TRIGGER trg_research_experiment_chain_forward_only
BEFORE UPDATE ON research_experiment_chain_head
FOR EACH ROW
BEGIN
    DECLARE matching_next_event INT DEFAULT 0;
    IF NEW.registration_id<>OLD.registration_id
       OR NEW.last_event_sequence<>OLD.last_event_sequence+1
       OR NEW.version<>OLD.version+1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'research experiment chain head must advance exactly one event';
    END IF;
    SELECT COUNT(*) INTO matching_next_event
      FROM research_experiment_event
     WHERE registration_id=OLD.registration_id
       AND event_sequence=NEW.last_event_sequence
       AND event_sha256=NEW.last_event_sha256
       AND previous_event_sha256=OLD.last_event_sha256;
    IF matching_next_event<>1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'research experiment chain head target is not the exact next event';
    END IF;
END$$
DELIMITER ;

CREATE TRIGGER trg_research_experiment_artifact_no_update
BEFORE UPDATE ON research_experiment_artifact
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'research experiment artifacts are immutable';

CREATE TRIGGER trg_research_experiment_artifact_no_delete
BEFORE DELETE ON research_experiment_artifact
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'research experiment artifacts are immutable';

CREATE TRIGGER trg_research_experiment_trial_no_update
BEFORE UPDATE ON research_experiment_trial
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'research experiment trials are immutable';

CREATE TRIGGER trg_research_experiment_trial_no_delete
BEFORE DELETE ON research_experiment_trial
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'research experiment trials are immutable';

CREATE TRIGGER trg_research_experiment_event_no_update
BEFORE UPDATE ON research_experiment_event
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'research experiment events are immutable';

CREATE TRIGGER trg_research_experiment_event_no_delete
BEFORE DELETE ON research_experiment_event
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'research experiment events are immutable';
