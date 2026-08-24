-- Long-term order-decision lineage schema island.
-- NOT_WIRED: the existing signal_event and oms_order mutation paths are not immutable facts and
-- are not made immutable by this migration. A legacy OMS row with no exact envelope binding is
-- explicitly not long-term-lineage compliant. No trigger here authorizes or dispatches an order.

CREATE TABLE order_decision_envelope (
    envelope_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    envelope_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

    market_event_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    market_event_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    journal_entry_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    journal_entry_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    archive_manifest_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    archive_manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    factor_snapshot_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    factor_snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    signal_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    signal_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

    strategy_id VARCHAR(100) NOT NULL,
    release_version VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    release_assignment_id VARCHAR(64) NOT NULL,
    release_assignment_version BIGINT NOT NULL,
    strategy_artifact_sha256 CHAR(64) NOT NULL,
    governance_subject_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

    git_commit_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    build_artifact_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    container_image_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    configuration_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

    producer_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    producer_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    proposal_id VARCHAR(128) COLLATE utf8mb4_bin NULL,
    proposal_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    model_version VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    prompt_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    context_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    tool_trace_root_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,

    instrument_namespace VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    asset_class VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    instrument_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    instrument_specification_version VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    instrument_specification_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

    account_snapshot_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    account_snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_snapshot_observed_at_ms BIGINT NOT NULL,
    risk_snapshot_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    risk_snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    risk_snapshot_observed_at_ms BIGINT NOT NULL,
    valuation_snapshot_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    valuation_snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    valuation_snapshot_observed_at_ms BIGINT NOT NULL,

    capital_policy_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    capital_policy_version VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    capital_policy_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    risk_policy_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    risk_policy_version VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    risk_policy_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    execution_policy_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    execution_policy_version VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    execution_policy_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

    approval_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    approval_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    capability_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    capability_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    authorization_subject_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    capability_not_before_ms BIGINT NOT NULL,
    capability_expires_at_ms BIGINT NOT NULL,

    order_id VARCHAR(64) NOT NULL,
    client_order_id VARCHAR(64) NOT NULL,
    command_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    command_schema_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    command_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at_ms BIGINT NOT NULL,
    not_before_ms BIGINT NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    create_time TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    UNIQUE KEY uk_order_decision_envelope_digest (envelope_sha256),
    UNIQUE KEY uk_order_decision_envelope_exact
        (envelope_id, envelope_sha256, order_id, client_order_id, command_sha256),
    INDEX idx_order_decision_strategy_time (strategy_id, created_at_ms),
    INDEX idx_order_decision_instrument_time (instrument_id, created_at_ms),
    CONSTRAINT fk_order_decision_release
        FOREIGN KEY (release_assignment_id, release_assignment_version, strategy_id,
                     strategy_artifact_sha256, governance_subject_sha256)
        REFERENCES strategy_release_assignment
            (assignment_id, assignment_version, strategy_name,
             artifact_sha256, governance_subject_sha256)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_order_decision_content_address CHECK (
        envelope_id=envelope_sha256
        AND envelope_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_order_decision_hashes CHECK (
        market_event_sha256 REGEXP '^[0-9a-f]{64}$'
        AND journal_entry_sha256 REGEXP '^[0-9a-f]{64}$'
        AND archive_manifest_sha256 REGEXP '^[0-9a-f]{64}$'
        AND factor_snapshot_sha256 REGEXP '^[0-9a-f]{64}$'
        AND signal_sha256 REGEXP '^[0-9a-f]{64}$'
        AND BINARY strategy_artifact_sha256 REGEXP '^[0-9a-f]{64}$'
        AND governance_subject_sha256 REGEXP '^[0-9a-f]{64}$'
        AND build_artifact_sha256 REGEXP '^[0-9a-f]{64}$'
        AND container_image_sha256 REGEXP '^[0-9a-f]{64}$'
        AND configuration_sha256 REGEXP '^[0-9a-f]{64}$'
        AND instrument_specification_sha256 REGEXP '^[0-9a-f]{64}$'
        AND account_snapshot_sha256 REGEXP '^[0-9a-f]{64}$'
        AND risk_snapshot_sha256 REGEXP '^[0-9a-f]{64}$'
        AND valuation_snapshot_sha256 REGEXP '^[0-9a-f]{64}$'
        AND capital_policy_sha256 REGEXP '^[0-9a-f]{64}$'
        AND risk_policy_sha256 REGEXP '^[0-9a-f]{64}$'
        AND execution_policy_sha256 REGEXP '^[0-9a-f]{64}$'
        AND approval_sha256 REGEXP '^[0-9a-f]{64}$'
        AND capability_sha256 REGEXP '^[0-9a-f]{64}$'
        AND authorization_subject_sha256 REGEXP '^[0-9a-f]{64}$'
        AND command_sha256 REGEXP '^[0-9a-f]{64}$'
        AND git_commit_hash REGEXP '^([0-9a-f]{40}|[0-9a-f]{64})$'),
    CONSTRAINT chk_order_decision_producer CHECK (
        (producer_kind='DETERMINISTIC' AND model_version='MODEL_NONE'
         AND proposal_id IS NULL AND proposal_sha256 IS NULL
         AND prompt_sha256 IS NULL AND context_sha256 IS NULL
         AND tool_trace_root_sha256 IS NULL)
        OR
        (producer_kind='AGENT' AND model_version<>'MODEL_NONE'
         AND proposal_id IS NOT NULL AND CHAR_LENGTH(TRIM(proposal_id))>0
         AND proposal_sha256 IS NOT NULL
         AND proposal_sha256 REGEXP '^[0-9a-f]{64}$'
         AND prompt_sha256 IS NOT NULL
         AND prompt_sha256 REGEXP '^[0-9a-f]{64}$'
         AND context_sha256 IS NOT NULL
         AND context_sha256 REGEXP '^[0-9a-f]{64}$'
         AND tool_trace_root_sha256 IS NOT NULL
         AND tool_trace_root_sha256 REGEXP '^[0-9a-f]{64}$')),
    CONSTRAINT chk_order_decision_time CHECK (
        created_at_ms>0 AND not_before_ms>=created_at_ms
        AND capability_not_before_ms>0
        AND capability_expires_at_ms>capability_not_before_ms
        AND expires_at_ms>not_before_ms
        AND expires_at_ms-created_at_ms<=900000
        AND not_before_ms>=capability_not_before_ms
        AND expires_at_ms<=capability_expires_at_ms
        AND account_snapshot_observed_at_ms>0
        AND account_snapshot_observed_at_ms<=created_at_ms
        AND risk_snapshot_observed_at_ms>0
        AND risk_snapshot_observed_at_ms<=created_at_ms
        AND valuation_snapshot_observed_at_ms>0
        AND valuation_snapshot_observed_at_ms<=created_at_ms),
    CONSTRAINT chk_order_decision_identity CHECK (
        CHAR_LENGTH(TRIM(market_event_id))>0
        AND CHAR_LENGTH(TRIM(journal_entry_id))>0
        AND CHAR_LENGTH(TRIM(archive_manifest_id))>0
        AND CHAR_LENGTH(TRIM(factor_snapshot_id))>0
        AND CHAR_LENGTH(TRIM(signal_id))>0
        AND CHAR_LENGTH(TRIM(strategy_id))>0
        AND CHAR_LENGTH(TRIM(release_version))>0
        AND CHAR_LENGTH(TRIM(release_assignment_id))>0
        AND CHAR_LENGTH(TRIM(producer_id))>0
        AND CHAR_LENGTH(TRIM(model_version))>0
        AND CHAR_LENGTH(TRIM(instrument_namespace))>0
        AND CHAR_LENGTH(TRIM(asset_class))>0
        AND CHAR_LENGTH(TRIM(instrument_id))>0
        AND CHAR_LENGTH(TRIM(instrument_specification_version))>0
        AND CHAR_LENGTH(TRIM(account_snapshot_id))>0
        AND CHAR_LENGTH(TRIM(risk_snapshot_id))>0
        AND CHAR_LENGTH(TRIM(valuation_snapshot_id))>0
        AND CHAR_LENGTH(TRIM(capital_policy_id))>0
        AND CHAR_LENGTH(TRIM(capital_policy_version))>0
        AND CHAR_LENGTH(TRIM(risk_policy_id))>0
        AND CHAR_LENGTH(TRIM(risk_policy_version))>0
        AND CHAR_LENGTH(TRIM(execution_policy_id))>0
        AND CHAR_LENGTH(TRIM(execution_policy_version))>0
        AND CHAR_LENGTH(TRIM(approval_id))>0
        AND CHAR_LENGTH(TRIM(capability_id))>0
        AND CHAR_LENGTH(TRIM(order_id))>0
        AND CHAR_LENGTH(TRIM(client_order_id))>0
        AND CHAR_LENGTH(TRIM(command_id))>0
        AND CHAR_LENGTH(TRIM(command_schema_version))>0)
) ENGINE=InnoDB COMMENT='Append-only content-addressed order-decision lineage; NOT_WIRED';

ALTER TABLE oms_order
    ADD COLUMN decision_envelope_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER strategy_artifact_sha256,
    ADD COLUMN decision_envelope_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER decision_envelope_id,
    ADD UNIQUE KEY uk_oms_order_decision_once (decision_envelope_id),
    ADD UNIQUE KEY uk_oms_order_decision_exact
        (order_id, decision_envelope_id, decision_envelope_sha256, command_sha256),
    ADD INDEX idx_oms_order_decision_parent
        (decision_envelope_id, decision_envelope_sha256,
         order_id, client_order_id, command_sha256),
    ADD CONSTRAINT fk_oms_order_decision_exact
        FOREIGN KEY (decision_envelope_id, decision_envelope_sha256,
                     order_id, client_order_id, command_sha256)
        REFERENCES order_decision_envelope
            (envelope_id, envelope_sha256, order_id, client_order_id, command_sha256)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT chk_oms_order_decision_pair CHECK (
        (decision_envelope_id IS NULL AND decision_envelope_sha256 IS NULL)
        OR
        (decision_envelope_id IS NOT NULL
         AND decision_envelope_sha256 IS NOT NULL
         AND decision_envelope_id=decision_envelope_sha256
         AND decision_envelope_id REGEXP '^[0-9a-f]{64}$'
         AND command_sha256 IS NOT NULL
         AND command_sha256 REGEXP '^[0-9a-f]{64}$'));

CREATE TABLE order_decision_wire_dispatch_evidence (
    wire_dispatch_event_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    wire_dispatch_event_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    envelope_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    envelope_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id VARCHAR(64) NOT NULL,
    command_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sequence_no BIGINT NOT NULL,
    dispatch_event_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    writer_fence_token BIGINT NOT NULL,
    wire_request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    venue_response_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    venue_order_id VARCHAR(128) COLLATE utf8mb4_bin NULL,
    occurred_at_ms BIGINT NOT NULL,
    create_time TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_order_wire_dispatch_digest (wire_dispatch_event_sha256),
    UNIQUE KEY uk_order_wire_dispatch_sequence (order_id, sequence_no),
    CONSTRAINT fk_order_wire_dispatch_oms_exact
        FOREIGN KEY (order_id, envelope_id, envelope_sha256, command_sha256)
        REFERENCES oms_order
            (order_id, decision_envelope_id, decision_envelope_sha256, command_sha256)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_order_wire_dispatch_hashes CHECK (
        wire_dispatch_event_id=wire_dispatch_event_sha256
        AND wire_dispatch_event_sha256 REGEXP '^[0-9a-f]{64}$'
        AND envelope_id=envelope_sha256
        AND envelope_sha256 REGEXP '^[0-9a-f]{64}$'
        AND command_sha256 REGEXP '^[0-9a-f]{64}$'
        AND wire_request_sha256 REGEXP '^[0-9a-f]{64}$'
        AND (venue_response_sha256 IS NULL
             OR venue_response_sha256 REGEXP '^[0-9a-f]{64}$')),
    CONSTRAINT chk_order_wire_dispatch_event CHECK (
        sequence_no>=1 AND writer_fence_token>=0 AND occurred_at_ms>0
        AND dispatch_event_kind IN
            ('WIRE_ATTEMPTED','WIRE_ACKNOWLEDGED','WIRE_REJECTED','WIRE_RESULT_UNKNOWN')
        AND (dispatch_event_kind<>'WIRE_ACKNOWLEDGED' OR venue_order_id IS NOT NULL)
        AND (dispatch_event_kind IN ('WIRE_ATTEMPTED','WIRE_RESULT_UNKNOWN')
             OR venue_response_sha256 IS NOT NULL))
) ENGINE=InnoDB COMMENT='Append-only exact wire-dispatch evidence; never an execution authority';

CREATE TABLE order_decision_reconciliation_evidence (
    reconciliation_event_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    reconciliation_event_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    envelope_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    envelope_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id VARCHAR(64) NOT NULL,
    command_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reconciliation_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    oms_snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    venue_orders_snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    venue_fills_snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    balance_snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    position_snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ledger_snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    observed_at_ms BIGINT NOT NULL,
    create_time TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_order_reconciliation_digest (reconciliation_event_sha256),
    UNIQUE KEY uk_order_reconciliation_observation
        (order_id, observed_at_ms, reconciliation_event_id),
    CONSTRAINT fk_order_reconciliation_oms_exact
        FOREIGN KEY (order_id, envelope_id, envelope_sha256, command_sha256)
        REFERENCES oms_order
            (order_id, decision_envelope_id, decision_envelope_sha256, command_sha256)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_order_reconciliation_hashes CHECK (
        reconciliation_event_id=reconciliation_event_sha256
        AND reconciliation_event_sha256 REGEXP '^[0-9a-f]{64}$'
        AND envelope_id=envelope_sha256
        AND envelope_sha256 REGEXP '^[0-9a-f]{64}$'
        AND command_sha256 REGEXP '^[0-9a-f]{64}$'
        AND oms_snapshot_sha256 REGEXP '^[0-9a-f]{64}$'
        AND venue_orders_snapshot_sha256 REGEXP '^[0-9a-f]{64}$'
        AND venue_fills_snapshot_sha256 REGEXP '^[0-9a-f]{64}$'
        AND balance_snapshot_sha256 REGEXP '^[0-9a-f]{64}$'
        AND position_snapshot_sha256 REGEXP '^[0-9a-f]{64}$'
        AND ledger_snapshot_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_order_reconciliation_kind CHECK (
        reconciliation_kind IN ('MATCHED','DIVERGED','FROZEN','REPAIRED')
        AND observed_at_ms>0)
) ENGINE=InnoDB COMMENT='Append-only OMS venue account and ledger reconciliation evidence';

DELIMITER $$
CREATE TRIGGER trg_order_decision_release_bind_exact
BEFORE INSERT ON order_decision_envelope
FOR EACH ROW
BEGIN
    DECLARE exact_release_count INT DEFAULT 0;
    SELECT COUNT(*) INTO exact_release_count
      FROM strategy_release_assignment
     WHERE BINARY assignment_id=BINARY NEW.release_assignment_id
       AND assignment_version=NEW.release_assignment_version
       AND BINARY strategy_name=BINARY NEW.strategy_id
       AND BINARY artifact_sha256=BINARY NEW.strategy_artifact_sha256
       AND governance_subject_sha256=NEW.governance_subject_sha256;
    IF exact_release_count<>1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT='order decision requires exact binary release lineage';
    END IF;
END$$
DELIMITER ;

CREATE TRIGGER trg_order_decision_envelope_no_update
BEFORE UPDATE ON order_decision_envelope FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='order-decision envelopes are immutable';

CREATE TRIGGER trg_order_decision_envelope_no_delete
BEFORE DELETE ON order_decision_envelope FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='order-decision envelopes are immutable';

CREATE TRIGGER trg_order_wire_dispatch_no_update
BEFORE UPDATE ON order_decision_wire_dispatch_evidence FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='order wire-dispatch evidence is immutable';

CREATE TRIGGER trg_order_wire_dispatch_no_delete
BEFORE DELETE ON order_decision_wire_dispatch_evidence FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='order wire-dispatch evidence is immutable';

CREATE TRIGGER trg_order_reconciliation_no_update
BEFORE UPDATE ON order_decision_reconciliation_evidence FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='order reconciliation evidence is immutable';

CREATE TRIGGER trg_order_reconciliation_no_delete
BEFORE DELETE ON order_decision_reconciliation_evidence FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='order reconciliation evidence is immutable';
