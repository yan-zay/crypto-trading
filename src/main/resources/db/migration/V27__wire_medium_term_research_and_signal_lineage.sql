-- Exact experiment identity may be referenced by every managed backtest fact.
ALTER TABLE research_experiment_registration
    ADD UNIQUE KEY uk_research_experiment_exact
        (registration_id, registration_sha256);

-- Persist lifecycle routing so SHADOW facts can never silently reach Paper execution.
-- LEGACY exists only to preserve historical rows created before the lifecycle routing column.
ALTER TABLE signal_event
    ADD COLUMN lifecycle_stage VARCHAR(20) NOT NULL DEFAULT 'LEGACY'
        AFTER governance_subject_sha256,
    ADD COLUMN delivery_target VARCHAR(20) NOT NULL DEFAULT 'LEGACY'
        AFTER lifecycle_stage,
    ADD INDEX idx_signal_delivery (delivery_target, lifecycle_stage, signal_time),
    ADD CONSTRAINT chk_signal_lifecycle_delivery CHECK (
        (lifecycle_stage='LEGACY' AND delivery_target='LEGACY')
        OR (lifecycle_stage='SHADOW' AND delivery_target='SHADOW')
        OR (lifecycle_stage IN ('CANARY','PROMOTED') AND delivery_target='PAPER'));

ALTER TABLE backtest_run
    ADD COLUMN research_registration_id VARCHAR(64) NULL AFTER strategy_config_json,
    ADD COLUMN research_registration_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER research_registration_id,
    ADD COLUMN research_trial_id VARCHAR(100) NULL AFTER research_registration_sha256,
    ADD COLUMN research_provenance_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER research_trial_id,
    ADD INDEX idx_backtest_run_research
        (research_registration_id, research_trial_id),
    ADD CONSTRAINT fk_backtest_run_research_exact
        FOREIGN KEY (research_registration_id, research_registration_sha256)
        REFERENCES research_experiment_registration
            (registration_id, registration_sha256)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT fk_backtest_run_research_trial
        FOREIGN KEY (research_registration_id, research_trial_id)
        REFERENCES research_experiment_trial (registration_id, trial_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT chk_backtest_run_research_lineage CHECK (
        (research_registration_id IS NULL
         AND research_registration_sha256 IS NULL
         AND research_trial_id IS NULL
         AND research_provenance_sha256 IS NULL)
        OR
        (research_registration_id IS NOT NULL
         AND research_registration_sha256 REGEXP '^[0-9a-f]{64}$'
         AND research_trial_id IS NOT NULL
         AND research_provenance_sha256 REGEXP '^[0-9a-f]{64}$'));

ALTER TABLE backtest_job
    ADD COLUMN research_registration_id VARCHAR(64) NULL AFTER random_seed,
    ADD COLUMN research_registration_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER research_registration_id,
    ADD COLUMN research_trial_id VARCHAR(100) NULL AFTER research_registration_sha256,
    ADD INDEX idx_backtest_job_research
        (research_registration_id, research_trial_id),
    ADD CONSTRAINT fk_backtest_job_research_exact
        FOREIGN KEY (research_registration_id, research_registration_sha256)
        REFERENCES research_experiment_registration
            (registration_id, registration_sha256)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT fk_backtest_job_research_trial
        FOREIGN KEY (research_registration_id, research_trial_id)
        REFERENCES research_experiment_trial (registration_id, trial_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT chk_backtest_job_research_lineage CHECK (
        (research_registration_id IS NULL
         AND research_registration_sha256 IS NULL
         AND research_trial_id IS NULL)
        OR
        (research_registration_id IS NOT NULL
         AND research_registration_sha256 REGEXP '^[0-9a-f]{64}$'
         AND research_trial_id IS NOT NULL));

ALTER TABLE agent_tool_audit
    ADD COLUMN model_output_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER prompt_version,
    ADD COLUMN eval_report_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER model_output_sha256,
    ADD CONSTRAINT chk_agent_model_trace_hashes CHECK (
        (model_output_sha256 IS NULL AND eval_report_sha256 IS NULL)
        OR
        (model_output_sha256 REGEXP '^[0-9a-f]{64}$'
         AND eval_report_sha256 REGEXP '^[0-9a-f]{64}$'));

CREATE TABLE agent_paper_shadow_proposal_use (
    proposal_id VARCHAR(73) PRIMARY KEY,
    proposal_use_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_environment VARCHAR(16) NOT NULL,
    strategy_name VARCHAR(100) NOT NULL,
    strategy_artifact_sha256 CHAR(64) NOT NULL,
    release_assignment_id VARCHAR(64) NOT NULL,
    instrument_scope_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    governance_subject_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    activated_by VARCHAR(100) NOT NULL,
    activated_at_ms BIGINT NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    live_execution_authorized BIT(1) NOT NULL DEFAULT b'0',
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_agent_proposal_use_digest (proposal_use_sha256),
    INDEX idx_agent_proposal_use_expiry (expires_at_ms),
    CONSTRAINT fk_agent_proposal_use_proposal
        FOREIGN KEY (proposal_id) REFERENCES agent_paper_shadow_proposal(proposal_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_agent_proposal_use_release
        FOREIGN KEY (release_assignment_id, strategy_name,
                     strategy_artifact_sha256, governance_subject_sha256)
        REFERENCES strategy_release_assignment
            (assignment_id, strategy_name, artifact_sha256, governance_subject_sha256)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_agent_proposal_use_target CHECK (
        target_environment IN ('PAPER','SHADOW')),
    CONSTRAINT chk_agent_proposal_use_hashes CHECK (
        proposal_use_sha256 REGEXP '^[0-9a-f]{64}$'
        AND strategy_artifact_sha256 REGEXP '^[0-9a-f]{64}$'
        AND instrument_scope_sha256 REGEXP '^[0-9a-f]{64}$'
        AND governance_subject_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_agent_proposal_use_window CHECK (
        activated_at_ms>0 AND expires_at_ms>activated_at_ms),
    CONSTRAINT chk_agent_proposal_use_no_live CHECK (live_execution_authorized=b'0')
) ENGINE=InnoDB COMMENT='Single-use bounded Agent L2 Paper/Shadow activation; never live authority';

CREATE TRIGGER trg_agent_proposal_use_no_update
BEFORE UPDATE ON agent_paper_shadow_proposal_use FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Agent proposal evaluation uses are immutable';

CREATE TRIGGER trg_agent_proposal_use_no_delete
BEFORE DELETE ON agent_paper_shadow_proposal_use FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Agent proposal evaluation uses are immutable';

DROP TRIGGER trg_signal_event_require_release_lineage;

DELIMITER $$
CREATE TRIGGER trg_signal_event_require_release_lineage
BEFORE INSERT ON signal_event
FOR EACH ROW
BEGIN
    IF NEW.release_assignment_id IS NULL
       OR NEW.strategy_artifact_sha256 IS NULL
       OR NEW.governance_subject_sha256 IS NULL
       OR NEW.lifecycle_stage NOT IN ('SHADOW','CANARY','PROMOTED')
       OR (NEW.lifecycle_stage='SHADOW' AND NEW.delivery_target<>'SHADOW')
       OR (NEW.lifecycle_stage IN ('CANARY','PROMOTED') AND NEW.delivery_target<>'PAPER') THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT='new signal events require exact lifecycle lineage and delivery routing';
    END IF;
END$$
DELIMITER ;
