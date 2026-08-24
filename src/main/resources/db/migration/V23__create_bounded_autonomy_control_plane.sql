-- Long-term bounded-autonomy evidence contract. This migration deliberately inserts no rows:
-- a fresh database therefore has no L4 capability that can be exercised.
-- V23 is a NOT_WIRED schema island: it does not ALTER or reference OMS orders, live-risk
-- reservations, or signal events. Its policy, instrument-set, proposal and action digests reserve
-- future exact-lineage bindings; their presence is not runtime authorization or live evidence.
CREATE TABLE autonomy_account_scope (
    account_scope_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    account_scope_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    venue VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    environment VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id VARCHAR(192) COLLATE utf8mb4_bin NOT NULL,
    subaccount_id VARCHAR(192) COLLATE utf8mb4_bin NOT NULL,
    base_currency VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    legal_subject_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    registered_by VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    registered_at_ms BIGINT NOT NULL,
    create_time TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_autonomy_scope_digest (account_scope_sha256),
    UNIQUE KEY uk_autonomy_scope_venue_account
        (venue, environment, account_id, subaccount_id),
    UNIQUE KEY uk_autonomy_scope_exact
        (account_scope_id, account_scope_sha256),
    CONSTRAINT chk_autonomy_scope_hashes CHECK (
        account_scope_sha256 REGEXP '^[0-9a-f]{64}$'
        AND legal_subject_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_autonomy_scope_identity CHECK (
        CHAR_LENGTH(TRIM(account_scope_id))>0
        AND CHAR_LENGTH(TRIM(venue))>0 AND venue=UPPER(venue)
        AND environment IN ('PAPER','SHADOW','LIVE_CANARY')
        AND CHAR_LENGTH(TRIM(account_id))>0
        AND CHAR_LENGTH(TRIM(subaccount_id))>0
        AND account_id<>subaccount_id
        AND CHAR_LENGTH(TRIM(base_currency))>0 AND base_currency=UPPER(base_currency)
        AND CHAR_LENGTH(TRIM(registered_by))>0
        AND registered_at_ms>0)
) ENGINE=InnoDB COMMENT='Immutable isolated account and subaccount scopes';

CREATE TABLE autonomy_capital_policy_version (
    capital_policy_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    policy_version BIGINT NOT NULL,
    account_scope_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_scope_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    capital_policy_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    currency VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    max_order_notional DECIMAL(38,18) NOT NULL,
    max_gross_notional DECIMAL(38,18) NOT NULL,
    max_daily_loss DECIMAL(38,18) NOT NULL,
    max_open_orders INT NOT NULL,
    valid_from_ms BIGINT NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    predecessor_version BIGINT NULL,
    predecessor_slot BIGINT GENERATED ALWAYS AS
        (COALESCE(predecessor_version, 0)) STORED,
    issued_by VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    issued_at_ms BIGINT NOT NULL,
    create_time TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (capital_policy_id, policy_version),
    UNIQUE KEY uk_autonomy_capital_digest (capital_policy_sha256),
    UNIQUE KEY uk_autonomy_capital_successor
        (capital_policy_id, predecessor_slot),
    UNIQUE KEY uk_autonomy_capital_exact
        (capital_policy_id, policy_version, account_scope_id,
         account_scope_sha256, capital_policy_sha256),
    UNIQUE KEY uk_autonomy_capital_predecessor_ref
        (capital_policy_id, policy_version, account_scope_id),
    CONSTRAINT fk_autonomy_capital_scope
        FOREIGN KEY (account_scope_id, account_scope_sha256)
        REFERENCES autonomy_account_scope
            (account_scope_id, account_scope_sha256),
    CONSTRAINT fk_autonomy_capital_predecessor
        FOREIGN KEY (capital_policy_id, predecessor_version, account_scope_id)
        REFERENCES autonomy_capital_policy_version
            (capital_policy_id, policy_version, account_scope_id),
    CONSTRAINT chk_autonomy_capital_hashes CHECK (
        account_scope_sha256 REGEXP '^[0-9a-f]{64}$'
        AND capital_policy_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_autonomy_capital_chain CHECK (
        (policy_version=1 AND predecessor_version IS NULL)
        OR (policy_version>1 AND predecessor_version=policy_version-1)),
    CONSTRAINT chk_autonomy_capital_limits CHECK (
        max_order_notional>0
        AND max_gross_notional>=max_order_notional
        AND max_daily_loss>0 AND max_daily_loss<=max_gross_notional
        AND max_open_orders>0
        AND CHAR_LENGTH(TRIM(currency))>0 AND currency=UPPER(currency)),
    CONSTRAINT chk_autonomy_capital_window CHECK (
        valid_from_ms>0 AND expires_at_ms>valid_from_ms
        AND issued_at_ms>0 AND issued_at_ms<=valid_from_ms
        AND CHAR_LENGTH(TRIM(issued_by))>0)
) ENGINE=InnoDB COMMENT='Append-only versioned capital ceilings';

CREATE TABLE autonomy_risk_policy_version (
    risk_policy_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    policy_version BIGINT NOT NULL,
    account_scope_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_scope_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    capital_policy_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    capital_policy_version BIGINT NOT NULL,
    capital_policy_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    risk_policy_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    allowed_instrument_set_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    max_leverage DECIMAL(20,8) NOT NULL,
    max_concentration_bps INT NOT NULL,
    max_drawdown_bps INT NOT NULL,
    max_order_rate_per_minute INT NOT NULL,
    kill_switch_required TINYINT(1) NOT NULL,
    single_writer_required TINYINT(1) NOT NULL,
    valid_from_ms BIGINT NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    predecessor_version BIGINT NULL,
    predecessor_slot BIGINT GENERATED ALWAYS AS
        (COALESCE(predecessor_version, 0)) STORED,
    issued_by VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    issued_at_ms BIGINT NOT NULL,
    create_time TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (risk_policy_id, policy_version),
    UNIQUE KEY uk_autonomy_risk_digest (risk_policy_sha256),
    UNIQUE KEY uk_autonomy_risk_successor (risk_policy_id, predecessor_slot),
    UNIQUE KEY uk_autonomy_risk_exact
        (risk_policy_id, policy_version, account_scope_id, account_scope_sha256,
         capital_policy_id, capital_policy_version, capital_policy_sha256,
         risk_policy_sha256, allowed_instrument_set_sha256),
    UNIQUE KEY uk_autonomy_risk_predecessor_ref
        (risk_policy_id, policy_version, account_scope_id),
    CONSTRAINT fk_autonomy_risk_scope
        FOREIGN KEY (account_scope_id, account_scope_sha256)
        REFERENCES autonomy_account_scope
            (account_scope_id, account_scope_sha256),
    CONSTRAINT fk_autonomy_risk_capital
        FOREIGN KEY (capital_policy_id, capital_policy_version, account_scope_id,
                     account_scope_sha256, capital_policy_sha256)
        REFERENCES autonomy_capital_policy_version
            (capital_policy_id, policy_version, account_scope_id,
             account_scope_sha256, capital_policy_sha256),
    CONSTRAINT fk_autonomy_risk_predecessor
        FOREIGN KEY (risk_policy_id, predecessor_version, account_scope_id)
        REFERENCES autonomy_risk_policy_version
            (risk_policy_id, policy_version, account_scope_id),
    CONSTRAINT chk_autonomy_risk_hashes CHECK (
        account_scope_sha256 REGEXP '^[0-9a-f]{64}$'
        AND capital_policy_sha256 REGEXP '^[0-9a-f]{64}$'
        AND risk_policy_sha256 REGEXP '^[0-9a-f]{64}$'
        AND allowed_instrument_set_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_autonomy_risk_chain CHECK (
        (policy_version=1 AND predecessor_version IS NULL)
        OR (policy_version>1 AND predecessor_version=policy_version-1)),
    CONSTRAINT chk_autonomy_risk_limits CHECK (
        max_leverage>=1 AND max_leverage<=10
        AND max_concentration_bps>0 AND max_concentration_bps<=10000
        AND max_drawdown_bps>0 AND max_drawdown_bps<=10000
        AND max_order_rate_per_minute>0
        AND kill_switch_required=1 AND single_writer_required=1),
    CONSTRAINT chk_autonomy_risk_window CHECK (
        valid_from_ms>0 AND expires_at_ms>valid_from_ms
        AND issued_at_ms>0 AND issued_at_ms<=valid_from_ms
        AND CHAR_LENGTH(TRIM(issued_by))>0)
) ENGINE=InnoDB COMMENT='Append-only versioned hard-risk policy';

CREATE TABLE autonomy_l3_proposal (
    proposal_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    proposal_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_scope_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_scope_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    capital_policy_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    capital_policy_version BIGINT NOT NULL,
    capital_policy_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    risk_policy_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    risk_policy_version BIGINT NOT NULL,
    risk_policy_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    strategy_name VARCHAR(100) NOT NULL,
    strategy_artifact_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    instrument_set_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_allocation_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    governance_subject_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    proposal_kind VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    agent_level VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    model_version VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    prompt_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    context_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tool_trace_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    proposed_by VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    proposed_at_ms BIGINT NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    create_time TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_autonomy_l3_proposal_digest (proposal_sha256),
    UNIQUE KEY uk_autonomy_l3_proposal_exact
        (proposal_id, proposal_sha256, account_scope_id, account_scope_sha256,
         capital_policy_id, capital_policy_version, capital_policy_sha256,
         risk_policy_id, risk_policy_version, risk_policy_sha256,
         strategy_name, strategy_artifact_sha256, instrument_set_sha256,
         governance_subject_sha256),
    CONSTRAINT fk_autonomy_l3_scope
        FOREIGN KEY (account_scope_id, account_scope_sha256)
        REFERENCES autonomy_account_scope
            (account_scope_id, account_scope_sha256),
    CONSTRAINT fk_autonomy_l3_capital
        FOREIGN KEY (capital_policy_id, capital_policy_version, account_scope_id,
                     account_scope_sha256, capital_policy_sha256)
        REFERENCES autonomy_capital_policy_version
            (capital_policy_id, policy_version, account_scope_id,
             account_scope_sha256, capital_policy_sha256),
    CONSTRAINT fk_autonomy_l3_risk
        FOREIGN KEY (risk_policy_id, risk_policy_version, account_scope_id,
                     account_scope_sha256, capital_policy_id,
                     capital_policy_version, capital_policy_sha256,
                     risk_policy_sha256, instrument_set_sha256)
        REFERENCES autonomy_risk_policy_version
            (risk_policy_id, policy_version, account_scope_id,
             account_scope_sha256, capital_policy_id, capital_policy_version,
             capital_policy_sha256, risk_policy_sha256,
             allowed_instrument_set_sha256),
    CONSTRAINT fk_autonomy_l3_artifact
        FOREIGN KEY (strategy_artifact_sha256, strategy_name)
        REFERENCES strategy_artifact (artifact_sha256, strategy_name),
    CONSTRAINT chk_autonomy_l3_hashes CHECK (
        proposal_sha256 REGEXP '^[0-9a-f]{64}$'
        AND account_scope_sha256 REGEXP '^[0-9a-f]{64}$'
        AND capital_policy_sha256 REGEXP '^[0-9a-f]{64}$'
        AND risk_policy_sha256 REGEXP '^[0-9a-f]{64}$'
        AND strategy_artifact_sha256 REGEXP '^[0-9a-f]{64}$'
        AND instrument_set_sha256 REGEXP '^[0-9a-f]{64}$'
        AND target_allocation_sha256 REGEXP '^[0-9a-f]{64}$'
        AND governance_subject_sha256 REGEXP '^[0-9a-f]{64}$'
        AND prompt_sha256 REGEXP '^[0-9a-f]{64}$'
        AND context_sha256 REGEXP '^[0-9a-f]{64}$'
        AND tool_trace_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_autonomy_l3_kind CHECK (
        agent_level='L3'
        AND proposal_kind IN ('LIVE_CANARY','CAPITAL_REALLOCATION',
                              'PARAMETER_ADJUSTMENT','CHALLENGER_CANARY')),
    CONSTRAINT chk_autonomy_l3_window CHECK (
        proposed_at_ms>0 AND expires_at_ms>proposed_at_ms
        AND expires_at_ms-proposed_at_ms<=900000
        AND CHAR_LENGTH(TRIM(model_version))>0
        AND CHAR_LENGTH(TRIM(proposed_by))>0)
) ENGINE=InnoDB COMMENT='Immutable L3 advice; it has no execution authority';

CREATE TABLE autonomy_human_approval_event (
    approval_event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    approval_event_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    proposal_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    proposal_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_scope_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_scope_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    capital_policy_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    capital_policy_version BIGINT NOT NULL,
    capital_policy_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    risk_policy_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    risk_policy_version BIGINT NOT NULL,
    risk_policy_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    strategy_name VARCHAR(100) NOT NULL,
    strategy_artifact_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    instrument_set_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    governance_subject_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    governance_request_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    governance_action VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    decision VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    approvers_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    approved_at_ms BIGINT NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    create_time TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_autonomy_human_approval_proposal (proposal_id),
    UNIQUE KEY uk_autonomy_human_approval_digest (approval_event_sha256),
    UNIQUE KEY uk_autonomy_human_approval_request (governance_request_id),
    UNIQUE KEY uk_autonomy_human_approval_exact
        (approval_event_id, proposal_id, proposal_sha256,
         account_scope_id, account_scope_sha256,
         capital_policy_id, capital_policy_version, capital_policy_sha256,
         risk_policy_id, risk_policy_version, risk_policy_sha256,
         strategy_name, strategy_artifact_sha256, instrument_set_sha256,
         governance_subject_sha256),
    CONSTRAINT fk_autonomy_human_proposal
        FOREIGN KEY (proposal_id, proposal_sha256, account_scope_id,
                     account_scope_sha256, capital_policy_id,
                     capital_policy_version, capital_policy_sha256,
                     risk_policy_id, risk_policy_version, risk_policy_sha256,
                     strategy_name, strategy_artifact_sha256,
                     instrument_set_sha256, governance_subject_sha256)
        REFERENCES autonomy_l3_proposal
            (proposal_id, proposal_sha256, account_scope_id,
             account_scope_sha256, capital_policy_id, capital_policy_version,
             capital_policy_sha256, risk_policy_id, risk_policy_version,
             risk_policy_sha256, strategy_name, strategy_artifact_sha256,
             instrument_set_sha256, governance_subject_sha256),
    CONSTRAINT fk_autonomy_human_governance
        FOREIGN KEY (governance_request_id, governance_action,
                     proposal_sha256, governance_subject_sha256)
        REFERENCES governance_approval_consumption
            (request_id, approval_action, resource_sha256,
             governance_subject_sha256),
    CONSTRAINT chk_autonomy_human_hashes CHECK (
        approval_event_sha256 REGEXP '^[0-9a-f]{64}$'
        AND proposal_sha256 REGEXP '^[0-9a-f]{64}$'
        AND account_scope_sha256 REGEXP '^[0-9a-f]{64}$'
        AND capital_policy_sha256 REGEXP '^[0-9a-f]{64}$'
        AND risk_policy_sha256 REGEXP '^[0-9a-f]{64}$'
        AND strategy_artifact_sha256 REGEXP '^[0-9a-f]{64}$'
        AND instrument_set_sha256 REGEXP '^[0-9a-f]{64}$'
        AND governance_subject_sha256 REGEXP '^[0-9a-f]{64}$'
        AND approvers_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_autonomy_human_decision CHECK (
        decision='APPROVED' AND governance_action='CAPABILITY_TOKEN_ISSUE'),
    CONSTRAINT chk_autonomy_human_window CHECK (
        approved_at_ms>0 AND expires_at_ms>approved_at_ms
        AND expires_at_ms-approved_at_ms<=900000)
) ENGINE=InnoDB COMMENT='One immutable human approval fact bound to one L3 proposal';

CREATE TABLE autonomy_replay_reservation (
    replay_reservation_id VARCHAR(128) COLLATE utf8mb4_bin PRIMARY KEY,
    replay_purpose VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    resource_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reserved_by VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    reserved_at_ms BIGINT NOT NULL,
    create_time TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_autonomy_replay_exact
        (replay_reservation_id, replay_purpose, resource_sha256),
    UNIQUE KEY uk_autonomy_replay_resource (resource_sha256),
    CONSTRAINT chk_autonomy_replay_purpose CHECK (replay_purpose IN
        ('CONSUME_L4_CAPABILITY','REVOKE_L4_CAPABILITY','CANARY_DECISION',
         'WATCHDOG_EVENT','HUMAN_TAKEOVER')),
    CONSTRAINT chk_autonomy_replay_hash CHECK (
        resource_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_autonomy_replay_identity CHECK (
        CHAR_LENGTH(TRIM(replay_reservation_id))>0
        AND CHAR_LENGTH(TRIM(reserved_by))>0 AND reserved_at_ms>0)
) ENGINE=InnoDB COMMENT='Global replay reservation ledger for autonomy control actions';

CREATE TABLE autonomy_l4_capability (
    capability_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    capability_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    approval_event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    proposal_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    proposal_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_scope_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_scope_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    capital_policy_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    capital_policy_version BIGINT NOT NULL,
    capital_policy_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    risk_policy_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    risk_policy_version BIGINT NOT NULL,
    risk_policy_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    strategy_name VARCHAR(100) NOT NULL,
    strategy_artifact_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    instrument_set_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    governance_subject_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    capability_level VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    allowed_action VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    max_order_notional DECIMAL(38,18) NOT NULL,
    max_gross_notional DECIMAL(38,18) NOT NULL,
    max_daily_loss DECIMAL(38,18) NOT NULL,
    boundary_mutation_allowed TINYINT(1) NOT NULL,
    issued_by VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    issued_at_ms BIGINT NOT NULL,
    not_before_ms BIGINT NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    create_time TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_autonomy_l4_capability_digest (capability_sha256),
    UNIQUE KEY uk_autonomy_l4_capability_approval (approval_event_id),
    UNIQUE KEY uk_autonomy_l4_capability_proposal (proposal_id),
    UNIQUE KEY uk_autonomy_l4_capability_exact
        (capability_id, capability_sha256),
    CONSTRAINT fk_autonomy_l4_approval
        FOREIGN KEY (approval_event_id, proposal_id, proposal_sha256,
                     account_scope_id, account_scope_sha256,
                     capital_policy_id, capital_policy_version,
                     capital_policy_sha256, risk_policy_id,
                     risk_policy_version, risk_policy_sha256,
                     strategy_name, strategy_artifact_sha256,
                     instrument_set_sha256, governance_subject_sha256)
        REFERENCES autonomy_human_approval_event
            (approval_event_id, proposal_id, proposal_sha256,
             account_scope_id, account_scope_sha256,
             capital_policy_id, capital_policy_version,
             capital_policy_sha256, risk_policy_id, risk_policy_version,
             risk_policy_sha256, strategy_name, strategy_artifact_sha256,
             instrument_set_sha256, governance_subject_sha256),
    CONSTRAINT chk_autonomy_l4_hashes CHECK (
        capability_sha256 REGEXP '^[0-9a-f]{64}$'
        AND proposal_sha256 REGEXP '^[0-9a-f]{64}$'
        AND account_scope_sha256 REGEXP '^[0-9a-f]{64}$'
        AND capital_policy_sha256 REGEXP '^[0-9a-f]{64}$'
        AND risk_policy_sha256 REGEXP '^[0-9a-f]{64}$'
        AND strategy_artifact_sha256 REGEXP '^[0-9a-f]{64}$'
        AND instrument_set_sha256 REGEXP '^[0-9a-f]{64}$'
        AND governance_subject_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_autonomy_l4_authority CHECK (
        capability_level='L4'
        AND allowed_action IN ('SELECT_PREAPPROVED_TARGET','ADJUST_WITHIN_BOUND')
        AND boundary_mutation_allowed=0),
    CONSTRAINT chk_autonomy_l4_limits CHECK (
        max_order_notional>0
        AND max_gross_notional>=max_order_notional
        AND max_daily_loss>0 AND max_daily_loss<=max_gross_notional),
    CONSTRAINT chk_autonomy_l4_window CHECK (
        issued_at_ms>0 AND not_before_ms>=issued_at_ms
        AND expires_at_ms>not_before_ms
        AND expires_at_ms-not_before_ms<=900000
        AND CHAR_LENGTH(TRIM(issued_by))>0)
) ENGINE=InnoDB COMMENT='Short-lived bounded L4 grant containing only a digest';

CREATE TABLE autonomy_l4_capability_terminal_event (
    terminal_event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    capability_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    capability_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    replay_reservation_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    replay_purpose VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    reason_code VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at_ms BIGINT NOT NULL,
    create_time TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_autonomy_l4_terminal_capability (capability_id),
    UNIQUE KEY uk_autonomy_l4_terminal_action (action_sha256),
    CONSTRAINT fk_autonomy_l4_terminal_capability
        FOREIGN KEY (capability_id, capability_sha256)
        REFERENCES autonomy_l4_capability (capability_id, capability_sha256),
    CONSTRAINT fk_autonomy_l4_terminal_replay
        FOREIGN KEY (replay_reservation_id, replay_purpose, action_sha256)
        REFERENCES autonomy_replay_reservation
            (replay_reservation_id, replay_purpose, resource_sha256),
    CONSTRAINT chk_autonomy_l4_terminal_hashes CHECK (
        capability_sha256 REGEXP '^[0-9a-f]{64}$'
        AND action_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_autonomy_l4_terminal_kind CHECK (
        (event_kind='CONSUMED' AND replay_purpose='CONSUME_L4_CAPABILITY')
        OR (event_kind='REVOKED' AND replay_purpose='REVOKE_L4_CAPABILITY')),
    CONSTRAINT chk_autonomy_l4_terminal_identity CHECK (
        CHAR_LENGTH(TRIM(actor))>0 AND CHAR_LENGTH(TRIM(reason_code))>0
        AND occurred_at_ms>0)
) ENGINE=InnoDB COMMENT='Exactly one consume-or-revoke event per L4 grant';

CREATE TABLE autonomy_canary_decision_event (
    decision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    decision_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    decision_stream_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_scope_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_scope_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sequence_no BIGINT NOT NULL,
    predecessor_decision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    predecessor_sequence_no BIGINT NULL,
    predecessor_slot VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (COALESCE(predecessor_decision_id, '#ROOT#')) STORED,
    decision_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    champion_strategy_name VARCHAR(100) NOT NULL,
    champion_artifact_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    challenger_strategy_name VARCHAR(100) NULL,
    challenger_artifact_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    canary_allocation_bps INT NOT NULL,
    evidence_set_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    replay_reservation_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    replay_purpose VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    decided_by VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    decided_at_ms BIGINT NOT NULL,
    create_time TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_autonomy_canary_digest (decision_sha256),
    UNIQUE KEY uk_autonomy_canary_sequence
        (decision_stream_id, account_scope_id, sequence_no),
    UNIQUE KEY uk_autonomy_canary_successor
        (decision_stream_id, account_scope_id, predecessor_slot),
    UNIQUE KEY uk_autonomy_canary_predecessor_ref
        (decision_stream_id, account_scope_id, decision_id, sequence_no),
    CONSTRAINT fk_autonomy_canary_scope
        FOREIGN KEY (account_scope_id, account_scope_sha256)
        REFERENCES autonomy_account_scope
            (account_scope_id, account_scope_sha256),
    CONSTRAINT fk_autonomy_canary_predecessor
        FOREIGN KEY (decision_stream_id, account_scope_id,
                     predecessor_decision_id, predecessor_sequence_no)
        REFERENCES autonomy_canary_decision_event
            (decision_stream_id, account_scope_id, decision_id, sequence_no),
    CONSTRAINT fk_autonomy_canary_champion
        FOREIGN KEY (champion_artifact_sha256, champion_strategy_name)
        REFERENCES strategy_artifact (artifact_sha256, strategy_name),
    CONSTRAINT fk_autonomy_canary_challenger
        FOREIGN KEY (challenger_artifact_sha256, challenger_strategy_name)
        REFERENCES strategy_artifact (artifact_sha256, strategy_name),
    CONSTRAINT fk_autonomy_canary_replay
        FOREIGN KEY (replay_reservation_id, replay_purpose, decision_sha256)
        REFERENCES autonomy_replay_reservation
            (replay_reservation_id, replay_purpose, resource_sha256),
    CONSTRAINT chk_autonomy_canary_hashes CHECK (
        decision_sha256 REGEXP '^[0-9a-f]{64}$'
        AND account_scope_sha256 REGEXP '^[0-9a-f]{64}$'
        AND champion_artifact_sha256 REGEXP '^[0-9a-f]{64}$'
        AND (challenger_artifact_sha256 IS NULL
             OR challenger_artifact_sha256 REGEXP '^[0-9a-f]{64}$')
        AND evidence_set_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_autonomy_canary_chain CHECK (
        (sequence_no=1 AND predecessor_decision_id IS NULL
         AND predecessor_sequence_no IS NULL)
        OR (sequence_no>1 AND predecessor_decision_id IS NOT NULL
            AND predecessor_sequence_no=sequence_no-1)),
    CONSTRAINT chk_autonomy_canary_kind CHECK (
        decision_kind IN ('CHAMPION_SELECTED','CHALLENGER_STARTED','CANARY_STARTED',
                          'CANARY_PROMOTED','CANARY_ROLLED_BACK','AUTOMATIC_DOWNGRADE')
        AND replay_purpose='CANARY_DECISION'
        AND ((sequence_no=1 AND decision_kind='CHAMPION_SELECTED')
             OR (sequence_no>1 AND decision_kind<>'CHAMPION_SELECTED'))),
    CONSTRAINT chk_autonomy_canary_pair CHECK (
        (challenger_strategy_name IS NULL AND challenger_artifact_sha256 IS NULL)
        OR (challenger_strategy_name IS NOT NULL
            AND challenger_artifact_sha256 IS NOT NULL
            AND (challenger_strategy_name<>champion_strategy_name
                 OR challenger_artifact_sha256<>champion_artifact_sha256))),
    CONSTRAINT chk_autonomy_canary_allocation CHECK (
        (decision_kind='CHAMPION_SELECTED'
         AND challenger_artifact_sha256 IS NULL AND canary_allocation_bps=0)
        OR (decision_kind='CHALLENGER_STARTED'
            AND challenger_artifact_sha256 IS NOT NULL AND canary_allocation_bps=0)
        OR (decision_kind='CANARY_STARTED'
            AND challenger_artifact_sha256 IS NOT NULL
            AND canary_allocation_bps BETWEEN 1 AND 500)
        OR (decision_kind='CANARY_PROMOTED'
            AND challenger_artifact_sha256 IS NOT NULL AND canary_allocation_bps=10000)
        OR (decision_kind='CANARY_ROLLED_BACK'
            AND challenger_artifact_sha256 IS NOT NULL AND canary_allocation_bps=0)
        OR (decision_kind='AUTOMATIC_DOWNGRADE' AND canary_allocation_bps=0)),
    CONSTRAINT chk_autonomy_canary_identity CHECK (
        CHAR_LENGTH(TRIM(decision_stream_id))>0
        AND CHAR_LENGTH(TRIM(decided_by))>0 AND decided_at_ms>0)
) ENGINE=InnoDB COMMENT='Hash-bound champion challenger and canary decisions';

CREATE TABLE autonomy_watchdog_event (
    watchdog_event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    watchdog_event_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    watchdog_instance_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_scope_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_scope_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sequence_no BIGINT NOT NULL,
    predecessor_event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    predecessor_sequence_no BIGINT NULL,
    predecessor_slot VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (COALESCE(predecessor_event_id, '#ROOT#')) STORED,
    event_kind VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    control_action VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    observation_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    policy_snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    replay_reservation_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    replay_purpose VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at_ms BIGINT NOT NULL,
    create_time TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_autonomy_watchdog_digest (watchdog_event_sha256),
    UNIQUE KEY uk_autonomy_watchdog_sequence
        (watchdog_instance_id, account_scope_id, sequence_no),
    UNIQUE KEY uk_autonomy_watchdog_successor
        (watchdog_instance_id, account_scope_id, predecessor_slot),
    UNIQUE KEY uk_autonomy_watchdog_predecessor_ref
        (watchdog_instance_id, account_scope_id, watchdog_event_id, sequence_no),
    CONSTRAINT fk_autonomy_watchdog_scope
        FOREIGN KEY (account_scope_id, account_scope_sha256)
        REFERENCES autonomy_account_scope
            (account_scope_id, account_scope_sha256),
    CONSTRAINT fk_autonomy_watchdog_predecessor
        FOREIGN KEY (watchdog_instance_id, account_scope_id,
                     predecessor_event_id, predecessor_sequence_no)
        REFERENCES autonomy_watchdog_event
            (watchdog_instance_id, account_scope_id,
             watchdog_event_id, sequence_no),
    CONSTRAINT fk_autonomy_watchdog_replay
        FOREIGN KEY (replay_reservation_id, replay_purpose, watchdog_event_sha256)
        REFERENCES autonomy_replay_reservation
            (replay_reservation_id, replay_purpose, resource_sha256),
    CONSTRAINT chk_autonomy_watchdog_hashes CHECK (
        watchdog_event_sha256 REGEXP '^[0-9a-f]{64}$'
        AND account_scope_sha256 REGEXP '^[0-9a-f]{64}$'
        AND observation_sha256 REGEXP '^[0-9a-f]{64}$'
        AND policy_snapshot_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_autonomy_watchdog_chain CHECK (
        (sequence_no=1 AND predecessor_event_id IS NULL
         AND predecessor_sequence_no IS NULL)
        OR (sequence_no>1 AND predecessor_event_id IS NOT NULL
            AND predecessor_sequence_no=sequence_no-1)),
    CONSTRAINT chk_autonomy_watchdog_kind CHECK (
        event_kind IN ('HEARTBEAT_MISSED','RISK_LIMIT_BREACH',
                       'RECONCILIATION_DIVERGENCE','POLICY_VIOLATION','RECOVERED')
        AND control_action IN ('FREEZE','DOWNGRADE','ALERT_ONLY')
        AND replay_purpose='WATCHDOG_EVENT'
        AND (event_kind<>'RECOVERED' OR control_action='ALERT_ONLY')),
    CONSTRAINT chk_autonomy_watchdog_identity CHECK (
        CHAR_LENGTH(TRIM(watchdog_instance_id))>0 AND occurred_at_ms>0)
) ENGINE=InnoDB COMMENT='Independent watchdog observations and fail-safe actions';

CREATE TABLE autonomy_human_takeover_event (
    takeover_event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    takeover_event_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    takeover_case_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_scope_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_scope_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sequence_no BIGINT NOT NULL,
    predecessor_event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    predecessor_sequence_no BIGINT NULL,
    predecessor_slot VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (COALESCE(predecessor_event_id, '#ROOT#')) STORED,
    takeover_action VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_set_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    governance_request_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NULL,
    governance_action VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NULL,
    governance_subject_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    replay_reservation_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    replay_purpose VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operator VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    occurred_at_ms BIGINT NOT NULL,
    create_time TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_autonomy_takeover_digest (takeover_event_sha256),
    UNIQUE KEY uk_autonomy_takeover_sequence
        (takeover_case_id, account_scope_id, sequence_no),
    UNIQUE KEY uk_autonomy_takeover_successor
        (takeover_case_id, account_scope_id, predecessor_slot),
    UNIQUE KEY uk_autonomy_takeover_predecessor_ref
        (takeover_case_id, account_scope_id, takeover_event_id, sequence_no),
    UNIQUE KEY uk_autonomy_takeover_governance (governance_request_id),
    CONSTRAINT fk_autonomy_takeover_scope
        FOREIGN KEY (account_scope_id, account_scope_sha256)
        REFERENCES autonomy_account_scope
            (account_scope_id, account_scope_sha256),
    CONSTRAINT fk_autonomy_takeover_predecessor
        FOREIGN KEY (takeover_case_id, account_scope_id,
                     predecessor_event_id, predecessor_sequence_no)
        REFERENCES autonomy_human_takeover_event
            (takeover_case_id, account_scope_id, takeover_event_id, sequence_no),
    CONSTRAINT fk_autonomy_takeover_governance
        FOREIGN KEY (governance_request_id, governance_action,
                     takeover_event_sha256, governance_subject_sha256)
        REFERENCES governance_approval_consumption
            (request_id, approval_action, resource_sha256,
             governance_subject_sha256),
    CONSTRAINT fk_autonomy_takeover_replay
        FOREIGN KEY (replay_reservation_id, replay_purpose, takeover_event_sha256)
        REFERENCES autonomy_replay_reservation
            (replay_reservation_id, replay_purpose, resource_sha256),
    CONSTRAINT chk_autonomy_takeover_hashes CHECK (
        takeover_event_sha256 REGEXP '^[0-9a-f]{64}$'
        AND account_scope_sha256 REGEXP '^[0-9a-f]{64}$'
        AND reason_sha256 REGEXP '^[0-9a-f]{64}$'
        AND evidence_set_sha256 REGEXP '^[0-9a-f]{64}$'
        AND (governance_subject_sha256 IS NULL
             OR governance_subject_sha256 REGEXP '^[0-9a-f]{64}$')),
    CONSTRAINT chk_autonomy_takeover_chain CHECK (
        (sequence_no=1 AND predecessor_event_id IS NULL
         AND predecessor_sequence_no IS NULL)
        OR (sequence_no>1 AND predecessor_event_id IS NOT NULL
            AND predecessor_sequence_no=sequence_no-1)),
    CONSTRAINT chk_autonomy_takeover_action CHECK (
        takeover_action IN ('TAKEOVER','ROLLBACK','RELEASE')
        AND replay_purpose='HUMAN_TAKEOVER'
        AND (sequence_no<>1 OR takeover_action='TAKEOVER')
        AND ((takeover_action IN ('TAKEOVER','ROLLBACK')
              AND governance_request_id IS NULL AND governance_action IS NULL
              AND governance_subject_sha256 IS NULL)
             OR (takeover_action='RELEASE'
                 AND governance_request_id IS NOT NULL
                 AND governance_action='LIVE_CANARY_ENABLE'
                 AND governance_subject_sha256 IS NOT NULL))),
    CONSTRAINT chk_autonomy_takeover_identity CHECK (
        CHAR_LENGTH(TRIM(takeover_case_id))>0
        AND CHAR_LENGTH(TRIM(operator))>0 AND occurred_at_ms>0)
) ENGINE=InnoDB COMMENT='Append-only emergency takeover rollback and approved release events';

DELIMITER $$
CREATE TRIGGER trg_autonomy_l4_validate_bounds
BEFORE INSERT ON autonomy_l4_capability
FOR EACH ROW
BEGIN
    DECLARE policy_max_order DECIMAL(38,18) DEFAULT NULL;
    DECLARE policy_max_gross DECIMAL(38,18) DEFAULT NULL;
    DECLARE policy_max_daily_loss DECIMAL(38,18) DEFAULT NULL;
    DECLARE capital_valid_from BIGINT DEFAULT NULL;
    DECLARE capital_expires BIGINT DEFAULT NULL;
    DECLARE risk_valid_from BIGINT DEFAULT NULL;
    DECLARE risk_expires BIGINT DEFAULT NULL;
    DECLARE proposal_time BIGINT DEFAULT NULL;
    DECLARE proposal_expires BIGINT DEFAULT NULL;
    DECLARE approval_time BIGINT DEFAULT NULL;
    DECLARE approval_expires BIGINT DEFAULT NULL;

    SELECT c.max_order_notional, c.max_gross_notional, c.max_daily_loss,
           c.valid_from_ms, c.expires_at_ms,
           r.valid_from_ms, r.expires_at_ms,
           p.proposed_at_ms, p.expires_at_ms,
           a.approved_at_ms, a.expires_at_ms
      INTO policy_max_order, policy_max_gross, policy_max_daily_loss,
           capital_valid_from, capital_expires, risk_valid_from, risk_expires,
           proposal_time, proposal_expires, approval_time, approval_expires
      FROM autonomy_human_approval_event a
      JOIN autonomy_l3_proposal p
        ON p.proposal_id=a.proposal_id AND p.proposal_sha256=a.proposal_sha256
      JOIN autonomy_capital_policy_version c
        ON c.capital_policy_id=a.capital_policy_id
       AND c.policy_version=a.capital_policy_version
       AND c.capital_policy_sha256=a.capital_policy_sha256
      JOIN autonomy_risk_policy_version r
        ON r.risk_policy_id=a.risk_policy_id
       AND r.policy_version=a.risk_policy_version
       AND r.risk_policy_sha256=a.risk_policy_sha256
     WHERE a.approval_event_id=NEW.approval_event_id
       AND a.proposal_id=NEW.proposal_id
       AND a.account_scope_id=NEW.account_scope_id;

    IF policy_max_order IS NULL THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT='L4 capability requires exact proposal approval and policies';
    END IF;
    IF NEW.max_order_notional>policy_max_order
       OR NEW.max_gross_notional>policy_max_gross
       OR NEW.max_daily_loss>policy_max_daily_loss THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT='L4 capability cannot exceed capital policy';
    END IF;
    IF NEW.issued_at_ms<proposal_time OR NEW.issued_at_ms<approval_time
       OR NEW.not_before_ms<capital_valid_from OR NEW.not_before_ms<risk_valid_from
       OR NEW.expires_at_ms>capital_expires OR NEW.expires_at_ms>risk_expires
       OR NEW.expires_at_ms>proposal_expires OR NEW.expires_at_ms>approval_expires THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT='L4 capability lies outside approval or policy validity';
    END IF;
END$$

CREATE TRIGGER trg_autonomy_l4_terminal_validate
BEFORE INSERT ON autonomy_l4_capability_terminal_event
FOR EACH ROW
BEGIN
    DECLARE capability_issued BIGINT DEFAULT NULL;
    DECLARE capability_not_before BIGINT DEFAULT NULL;
    DECLARE capability_expires BIGINT DEFAULT NULL;
    DECLARE replay_reserved BIGINT DEFAULT NULL;

    SELECT c.issued_at_ms, c.not_before_ms, c.expires_at_ms, r.reserved_at_ms
      INTO capability_issued, capability_not_before, capability_expires,
           replay_reserved
      FROM autonomy_l4_capability c
      JOIN autonomy_replay_reservation r
        ON r.replay_reservation_id=NEW.replay_reservation_id
       AND r.replay_purpose=NEW.replay_purpose
       AND r.resource_sha256=NEW.action_sha256
     WHERE c.capability_id=NEW.capability_id
       AND c.capability_sha256=NEW.capability_sha256;

    IF capability_issued IS NULL OR replay_reserved>NEW.occurred_at_ms THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT='L4 terminal event requires an earlier exact replay reservation';
    END IF;
    IF NEW.event_kind='CONSUMED'
       AND (NEW.occurred_at_ms<capability_not_before
            OR NEW.occurred_at_ms>=capability_expires) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT='L4 capability can only be consumed inside its validity window';
    END IF;
    IF NEW.event_kind='REVOKED' AND NEW.occurred_at_ms<capability_issued THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT='L4 capability cannot be revoked before issuance';
    END IF;
END$$

CREATE TRIGGER trg_autonomy_scope_no_update
BEFORE UPDATE ON autonomy_account_scope
FOR EACH ROW SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT='autonomy account scopes are immutable'$$
CREATE TRIGGER trg_autonomy_scope_no_delete
BEFORE DELETE ON autonomy_account_scope
FOR EACH ROW SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT='autonomy account scopes are immutable'$$

CREATE TRIGGER trg_autonomy_capital_no_update
BEFORE UPDATE ON autonomy_capital_policy_version
FOR EACH ROW SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT='autonomy capital policies are immutable'$$
CREATE TRIGGER trg_autonomy_capital_no_delete
BEFORE DELETE ON autonomy_capital_policy_version
FOR EACH ROW SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT='autonomy capital policies are immutable'$$

CREATE TRIGGER trg_autonomy_risk_no_update
BEFORE UPDATE ON autonomy_risk_policy_version
FOR EACH ROW SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT='autonomy risk policies are immutable'$$
CREATE TRIGGER trg_autonomy_risk_no_delete
BEFORE DELETE ON autonomy_risk_policy_version
FOR EACH ROW SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT='autonomy risk policies are immutable'$$

CREATE TRIGGER trg_autonomy_l3_no_update
BEFORE UPDATE ON autonomy_l3_proposal
FOR EACH ROW SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT='autonomy L3 proposals are immutable'$$
CREATE TRIGGER trg_autonomy_l3_no_delete
BEFORE DELETE ON autonomy_l3_proposal
FOR EACH ROW SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT='autonomy L3 proposals are immutable'$$

CREATE TRIGGER trg_autonomy_human_approval_no_update
BEFORE UPDATE ON autonomy_human_approval_event
FOR EACH ROW SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT='autonomy human approvals are immutable'$$
CREATE TRIGGER trg_autonomy_human_approval_no_delete
BEFORE DELETE ON autonomy_human_approval_event
FOR EACH ROW SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT='autonomy human approvals are immutable'$$

CREATE TRIGGER trg_autonomy_replay_no_update
BEFORE UPDATE ON autonomy_replay_reservation
FOR EACH ROW SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT='autonomy replay reservations are immutable'$$
CREATE TRIGGER trg_autonomy_replay_no_delete
BEFORE DELETE ON autonomy_replay_reservation
FOR EACH ROW SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT='autonomy replay reservations are immutable'$$

CREATE TRIGGER trg_autonomy_l4_no_update
BEFORE UPDATE ON autonomy_l4_capability
FOR EACH ROW SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT='autonomy L4 capabilities are immutable'$$
CREATE TRIGGER trg_autonomy_l4_no_delete
BEFORE DELETE ON autonomy_l4_capability
FOR EACH ROW SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT='autonomy L4 capabilities are immutable'$$

CREATE TRIGGER trg_autonomy_l4_terminal_no_update
BEFORE UPDATE ON autonomy_l4_capability_terminal_event
FOR EACH ROW SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT='autonomy L4 terminal events are immutable'$$
CREATE TRIGGER trg_autonomy_l4_terminal_no_delete
BEFORE DELETE ON autonomy_l4_capability_terminal_event
FOR EACH ROW SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT='autonomy L4 terminal events are immutable'$$

CREATE TRIGGER trg_autonomy_canary_no_update
BEFORE UPDATE ON autonomy_canary_decision_event
FOR EACH ROW SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT='autonomy canary decisions are immutable'$$
CREATE TRIGGER trg_autonomy_canary_no_delete
BEFORE DELETE ON autonomy_canary_decision_event
FOR EACH ROW SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT='autonomy canary decisions are immutable'$$

CREATE TRIGGER trg_autonomy_watchdog_no_update
BEFORE UPDATE ON autonomy_watchdog_event
FOR EACH ROW SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT='autonomy watchdog events are immutable'$$
CREATE TRIGGER trg_autonomy_watchdog_no_delete
BEFORE DELETE ON autonomy_watchdog_event
FOR EACH ROW SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT='autonomy watchdog events are immutable'$$

CREATE TRIGGER trg_autonomy_takeover_no_update
BEFORE UPDATE ON autonomy_human_takeover_event
FOR EACH ROW SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT='autonomy takeover events are immutable'$$
CREATE TRIGGER trg_autonomy_takeover_no_delete
BEFORE DELETE ON autonomy_human_takeover_event
FOR EACH ROW SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT='autonomy takeover events are immutable'$$
DELIMITER ;
