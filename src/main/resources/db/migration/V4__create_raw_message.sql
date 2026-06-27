-- 原始消息表（数据血缘 & 去重）
CREATE TABLE IF NOT EXISTS raw_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source VARCHAR(20) NOT NULL COMMENT '数据来源(exchange)',
    channel VARCHAR(50) NOT NULL COMMENT '频道(liquidationOrders/kline等)',
    symbol VARCHAR(30) NOT NULL COMMENT '交易对',
    raw_json MEDIUMTEXT NOT NULL COMMENT '原始JSON消息',
    received_time BIGINT NOT NULL COMMENT '接收时间(毫秒)',
    checksum VARCHAR(64) NOT NULL COMMENT 'SHA-256(raw_json) 用于去重',
    processed TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已处理(0=否,1=是)',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_checksum (checksum),
    INDEX idx_source_symbol_time (source, symbol, received_time),
    INDEX idx_processed (processed)
) ENGINE=InnoDB COMMENT='原始消息(数据血缘/去重)';
