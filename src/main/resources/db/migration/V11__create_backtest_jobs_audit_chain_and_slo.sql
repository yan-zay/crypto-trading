CREATE TABLE backtest_job (
    job_id VARCHAR(64) PRIMARY KEY,
    job_type VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    request_json JSON NOT NULL,
    progress_pct INT NOT NULL DEFAULT 0,
    stage VARCHAR(100) NULL,
    result_id VARCHAR(64) NULL,
    error_code VARCHAR(100) NULL,
    error_message VARCHAR(2000) NULL,
    random_seed BIGINT NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    created_at_ms BIGINT NOT NULL,
    started_at_ms BIGINT NULL,
    completed_at_ms BIGINT NULL,
    heartbeat_at_ms BIGINT NULL,
    worker_id VARCHAR(100) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_backtest_job_claim (status, created_at_ms),
    INDEX idx_backtest_job_owner_time (created_by, created_at_ms)
) ENGINE=InnoDB COMMENT='异步回测任务';

ALTER TABLE backtest_run
    ADD COLUMN robustness_json JSON NULL AFTER assumptions_json,
    ADD COLUMN reproducibility_json JSON NULL AFTER robustness_json,
    ADD COLUMN execution_quality_json JSON NULL AFTER reproducibility_json;

ALTER TABLE admin_audit_log
    MODIFY COLUMN operation_type VARCHAR(80) NOT NULL,
    MODIFY COLUMN operation_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    ADD COLUMN request_id VARCHAR(64) NULL AFTER id,
    ADD COLUMN correlation_id VARCHAR(64) NULL AFTER request_id,
    ADD COLUMN resource_type VARCHAR(50) NULL AFTER operation_type,
    ADD COLUMN resource_id VARCHAR(100) NULL AFTER resource_type,
    ADD COLUMN outcome VARCHAR(20) NOT NULL DEFAULT 'SUCCESS' AFTER operator,
    ADD COLUMN source_ip VARCHAR(64) NULL AFTER outcome,
    ADD COLUMN latency_ms BIGINT NULL AFTER source_ip,
    ADD COLUMN previous_hash VARCHAR(64) NULL AFTER detail,
    ADD COLUMN entry_hash VARCHAR(64) NULL AFTER previous_hash,
    ADD INDEX idx_audit_request (request_id),
    ADD INDEX idx_audit_resource (resource_type, resource_id, operation_time);

CREATE TABLE audit_chain_head (
    chain_name VARCHAR(50) PRIMARY KEY,
    last_audit_id BIGINT NULL,
    last_hash VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='审计 hash chain 串行化头';

INSERT IGNORE INTO audit_chain_head (chain_name, last_hash)
VALUES ('ADMIN', REPEAT('0', 64));

CREATE TABLE slo_snapshot (
    snapshot_id VARCHAR(64) PRIMARY KEY,
    slo_name VARCHAR(100) NOT NULL,
    window_start_ms BIGINT NOT NULL,
    window_end_ms BIGINT NOT NULL,
    target_value DECIMAL(20,8) NOT NULL,
    actual_value DECIMAL(20,8) NULL,
    compliant TINYINT(1) NOT NULL,
    error_budget_remaining_pct DECIMAL(20,8) NULL,
    sample_count BIGINT NOT NULL,
    detail_json JSON NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_slo_window (slo_name, window_start_ms, window_end_ms),
    INDEX idx_slo_time (window_end_ms)
) ENGINE=InnoDB COMMENT='SLO 周期快照';
