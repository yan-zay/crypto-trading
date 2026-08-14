-- A database-clock lease serializes all private venue write commands across JVMs.
-- fencing_token increases whenever ownership moves to a different process.
CREATE TABLE execution_writer_lease (
    lease_scope VARCHAR(80) PRIMARY KEY,
    owner_id VARCHAR(160) NOT NULL,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    lease_until_ms BIGINT NOT NULL DEFAULT 0,
    heartbeat_at_ms BIGINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_execution_writer_lease_until (lease_until_ms)
) ENGINE=InnoDB COMMENT='Private venue single-writer lease and fencing token';
