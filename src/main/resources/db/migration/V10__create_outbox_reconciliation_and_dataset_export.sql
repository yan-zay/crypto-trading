CREATE TABLE event_outbox (
    event_id VARCHAR(64) PRIMARY KEY,
    event_sequence BIGINT NOT NULL AUTO_INCREMENT,
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    payload_json JSON NOT NULL,
    correlation_id VARCHAR(64) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    available_at_ms BIGINT NOT NULL,
    claimed_by VARCHAR(100) NULL,
    claim_until_ms BIGINT NULL,
    published_at_ms BIGINT NULL,
    last_error VARCHAR(1000) NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_outbox_sequence (event_sequence),
    INDEX idx_outbox_claim (status, available_at_ms, claim_until_ms),
    INDEX idx_outbox_aggregate (aggregate_type, aggregate_id, create_time)
) ENGINE=InnoDB COMMENT='事务 outbox';

CREATE TABLE processed_event (
    consumer_name VARCHAR(100) NOT NULL,
    event_id VARCHAR(64) NOT NULL,
    processed_at_ms BIGINT NOT NULL,
    PRIMARY KEY (consumer_name, event_id)
) ENGINE=InnoDB COMMENT='outbox 消费幂等检查点';

CREATE TABLE reconciliation_incident (
    incident_id VARCHAR(64) PRIMARY KEY,
    account_id VARCHAR(64) NULL,
    incident_type VARCHAR(50) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    expected_json JSON NULL,
    actual_json JSON NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    detected_at_ms BIGINT NOT NULL,
    resolved_at_ms BIGINT NULL,
    resolution VARCHAR(1000) NULL,
    resolved_by VARCHAR(100) NULL,
    fingerprint VARCHAR(64) NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_reconciliation_open_fingerprint (fingerprint, status),
    INDEX idx_reconciliation_status_time (status, detected_at_ms),
    INDEX idx_reconciliation_account_time (account_id, detected_at_ms)
) ENGINE=InnoDB COMMENT='订单、成交、持仓、余额与账本对账差异';

CREATE TABLE dataset_export (
    export_id VARCHAR(64) PRIMARY KEY,
    export_type VARCHAR(40) NOT NULL,
    format VARCHAR(20) NOT NULL,
    request_json JSON NOT NULL,
    status VARCHAR(20) NOT NULL,
    row_count BIGINT NOT NULL DEFAULT 0,
    checksum VARCHAR(64) NULL,
    data_version VARCHAR(128) NULL,
    schema_version VARCHAR(30) NOT NULL DEFAULT 'v1',
    artifact_path VARCHAR(1000) NULL,
    error_message VARCHAR(1000) NULL,
    created_by VARCHAR(100) NOT NULL,
    created_at_ms BIGINT NOT NULL,
    completed_at_ms BIGINT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_dataset_export_status_time (status, created_at_ms)
) ENGINE=InnoDB COMMENT='研究数据导出 manifest';
