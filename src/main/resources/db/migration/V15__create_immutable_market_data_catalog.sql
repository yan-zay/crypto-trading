CREATE TABLE market_data_dataset (
    dataset_version_id VARCHAR(64) PRIMARY KEY,
    dataset_id VARCHAR(128) NOT NULL,
    dataset_version VARCHAR(128) NOT NULL,
    description VARCHAR(1000) NULL,
    schema_name VARCHAR(128) NOT NULL,
    schema_version VARCHAR(30) NOT NULL,
    schema_fingerprint_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    catalog_state VARCHAR(20) NOT NULL DEFAULT 'REGISTERED',
    created_by VARCHAR(100) NOT NULL,
    created_at_ms BIGINT NOT NULL,
    UNIQUE KEY uk_market_dataset_version (dataset_id, dataset_version),
    CONSTRAINT chk_market_dataset_state
        CHECK (catalog_state IN ('REGISTERED', 'VERIFIED', 'QUARANTINED')),
    CONSTRAINT chk_market_dataset_sha CHECK (schema_fingerprint_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_market_dataset_identity CHECK (
        CHAR_LENGTH(TRIM(dataset_id)) > 0 AND CHAR_LENGTH(TRIM(dataset_version)) > 0
    ),
    CONSTRAINT chk_market_dataset_created_at CHECK (created_at_ms > 0)
) ENGINE=InnoDB COMMENT='不可变行情数据集版本目录；新版本只能 INSERT';

CREATE TABLE market_data_object_catalog (
    object_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    storage_uri VARCHAR(1000) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    byte_size BIGINT NOT NULL,
    encryption_key_ref VARCHAR(255) NULL,
    storage_etag VARCHAR(255) NULL,
    verification_state VARCHAR(20) NOT NULL DEFAULT 'REGISTERED',
    verified_at_ms BIGINT NULL,
    created_at_ms BIGINT NOT NULL,
    UNIQUE KEY uk_market_object_uri (storage_uri),
    CONSTRAINT chk_market_object_size CHECK (byte_size > 0),
    CONSTRAINT chk_market_object_sha CHECK (object_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_market_object_uri CHECK (CHAR_LENGTH(TRIM(storage_uri)) > 0),
    CONSTRAINT chk_market_object_state
        CHECK (verification_state IN ('REGISTERED', 'VERIFIED', 'QUARANTINED')),
    CONSTRAINT chk_market_object_created_at CHECK (created_at_ms > 0)
) ENGINE=InnoDB COMMENT='内容寻址、不可覆盖的行情对象目录';

CREATE TABLE market_data_manifest (
    manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    dataset_version_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    manifest_version VARCHAR(30) NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    source VARCHAR(30) NOT NULL,
    exchange VARCHAR(30) NOT NULL,
    market_type VARCHAR(30) NOT NULL,
    symbol VARCHAR(80) NOT NULL,
    event_date_utc DATE NOT NULL,
    schema_fingerprint_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    data_object_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    row_count BIGINT NOT NULL,
    start_inclusive_ms BIGINT NOT NULL,
    end_exclusive_ms BIGINT NOT NULL,
    source_uri VARCHAR(1000) NOT NULL,
    immutable_source_snapshot_id VARCHAR(128) NOT NULL,
    writer_version VARCHAR(100) NOT NULL,
    created_at_ms BIGINT NOT NULL,
    UNIQUE KEY uk_market_manifest_idempotency (dataset_version_id, idempotency_key),
    UNIQUE KEY uk_market_manifest_partition_object (
        dataset_version_id, event_type, source, exchange, market_type,
        symbol, event_date_utc, data_object_sha256
    ),
    INDEX idx_market_manifest_partition (
        dataset_version_id, event_type, exchange, market_type, symbol, event_date_utc
    ),
    CONSTRAINT fk_market_manifest_dataset FOREIGN KEY (dataset_version_id)
        REFERENCES market_data_dataset(dataset_version_id),
    CONSTRAINT fk_market_manifest_object FOREIGN KEY (data_object_sha256)
        REFERENCES market_data_object_catalog(object_sha256),
    CONSTRAINT chk_market_manifest_rows CHECK (row_count > 0),
    CONSTRAINT chk_market_manifest_hashes CHECK (
        manifest_sha256 REGEXP '^[0-9a-f]{64}$'
        AND schema_fingerprint_sha256 REGEXP '^[0-9a-f]{64}$'
        AND request_sha256 REGEXP '^[0-9a-f]{64}$'
        AND data_object_sha256 REGEXP '^[0-9a-f]{64}$'
    ),
    CONSTRAINT chk_market_manifest_identity CHECK (
        CHAR_LENGTH(TRIM(idempotency_key)) > 0
        AND CHAR_LENGTH(TRIM(event_type)) > 0
        AND CHAR_LENGTH(TRIM(source)) > 0
        AND CHAR_LENGTH(TRIM(exchange)) > 0
        AND CHAR_LENGTH(TRIM(market_type)) > 0
        AND CHAR_LENGTH(TRIM(symbol)) > 0
        AND CHAR_LENGTH(TRIM(source_uri)) > 0
        AND CHAR_LENGTH(TRIM(immutable_source_snapshot_id)) > 0
    ),
    CONSTRAINT chk_market_manifest_range CHECK (
        start_inclusive_ms >= 0 AND end_exclusive_ms > start_inclusive_ms
    ),
    CONSTRAINT chk_market_manifest_created_at CHECK (created_at_ms > 0)
) ENGINE=InnoDB COMMENT='manifest-last 行情分区事实与 lineage 目录';

CREATE TRIGGER trg_market_data_dataset_no_update
BEFORE UPDATE ON market_data_dataset
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'market data dataset versions are immutable';

CREATE TRIGGER trg_market_data_dataset_no_delete
BEFORE DELETE ON market_data_dataset
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'market data dataset versions are immutable';

CREATE TRIGGER trg_market_data_object_no_update
BEFORE UPDATE ON market_data_object_catalog
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'market data objects are immutable';

CREATE TRIGGER trg_market_data_object_no_delete
BEFORE DELETE ON market_data_object_catalog
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'market data objects are immutable';

CREATE TRIGGER trg_market_data_manifest_no_update
BEFORE UPDATE ON market_data_manifest
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'market data manifests are immutable';

CREATE TRIGGER trg_market_data_manifest_no_delete
BEFORE DELETE ON market_data_manifest
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'market data manifests are immutable';
