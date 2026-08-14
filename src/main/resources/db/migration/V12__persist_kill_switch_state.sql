-- The singleton is deliberately seeded as HALT. Upgrading an older database must
-- never turn live trading on merely because the previous JVM-local state vanished.
CREATE TABLE kill_switch_state (
    state_key VARCHAR(40) PRIMARY KEY,
    mode VARCHAR(20) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    changed_by VARCHAR(100) NOT NULL,
    changed_at_ms BIGINT NOT NULL,
    state_version BIGINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_kill_switch_mode_time (mode, changed_at_ms)
) ENGINE=InnoDB COMMENT='持久化全局交易熔断状态';

INSERT INTO kill_switch_state
    (state_key, mode, reason, changed_by, changed_at_ms, state_version)
VALUES
    ('GLOBAL', 'HALT', 'FAIL_CLOSED_SCHEMA_UPGRADE', 'FLYWAY',
     CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED), 0)
ON DUPLICATE KEY UPDATE state_key=VALUES(state_key);
