-- 统一 V1 市场表与 PhysicsTimeBaseDO 的时间字段命名。
ALTER TABLE bar_event
    ADD COLUMN create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;
ALTER TABLE signal_event
    ADD COLUMN create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;
ALTER TABLE trade_record
    ADD COLUMN create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;

-- 领域标识和净收益字段：同名 symbol 在不同市场不能混为一谈。
ALTER TABLE signal_event
    ADD COLUMN market_type VARCHAR(20) NOT NULL DEFAULT 'PERPETUAL' AFTER exchange;
ALTER TABLE trade_record
    ADD COLUMN market_type VARCHAR(20) NOT NULL DEFAULT 'PERPETUAL' AFTER exchange,
    ADD COLUMN total_fee DECIMAL(38,18) NOT NULL DEFAULT 0 AFTER realized_pnl,
    ADD COLUMN net_pnl DECIMAL(38,18) NOT NULL DEFAULT 0 AFTER total_fee,
    ADD COLUMN strategy_id VARCHAR(100) NULL AFTER net_pnl,
    ADD COLUMN order_id VARCHAR(64) NULL AFTER strategy_id;

-- K 线使用自然键幂等：先保留最后写入的一条，再建立唯一约束。
DELETE old_bar FROM bar_event old_bar
INNER JOIN bar_event new_bar
    ON old_bar.exchange = new_bar.exchange
    AND old_bar.market_type = new_bar.market_type
    AND old_bar.symbol = new_bar.symbol
    AND old_bar.timeframe = new_bar.timeframe
    AND old_bar.open_time = new_bar.open_time
    AND old_bar.id < new_bar.id;
ALTER TABLE bar_event
    ADD UNIQUE KEY uk_bar_series_time
        (exchange, market_type, symbol, timeframe, open_time);

ALTER TABLE trade_record
    ADD UNIQUE KEY uk_trade_order (order_id),
    ADD INDEX idx_trade_instrument_time (exchange, market_type, symbol, exit_time);

-- OMS 订单快照。order_id 是内部主键，client_order_id 用于交易所请求幂等。
CREATE TABLE oms_order (
    order_id VARCHAR(64) PRIMARY KEY,
    client_order_id VARCHAR(64) NOT NULL,
    strategy_id VARCHAR(100) NOT NULL,
    exchange VARCHAR(20) NOT NULL,
    market_type VARCHAR(20) NOT NULL,
    symbol VARCHAR(40) NOT NULL,
    trade_side VARCHAR(8) NOT NULL COMMENT 'BUY/SELL',
    requested_side VARCHAR(8) NOT NULL COMMENT '策略请求 LONG/SHORT',
    position_side VARCHAR(8) NOT NULL COMMENT '实际作用的 LONG/SHORT 仓位',
    reduce_only TINYINT(1) NOT NULL,
    order_type VARCHAR(20) NOT NULL,
    quantity DECIMAL(38,18) NOT NULL,
    price DECIMAL(38,18) NULL,
    filled_quantity DECIMAL(38,18) NOT NULL DEFAULT 0,
    avg_fill_price DECIMAL(38,18) NULL,
    status VARCHAR(30) NOT NULL,
    reject_reason VARCHAR(50) NULL,
    created_at_ms BIGINT NOT NULL,
    submitted_at_ms BIGINT NULL,
    filled_at_ms BIGINT NULL,
    cancelled_at_ms BIGINT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_oms_client_order (client_order_id),
    INDEX idx_oms_instrument_time (exchange, market_type, symbol, created_at_ms),
    INDEX idx_oms_status_time (status, created_at_ms),
    INDEX idx_oms_strategy_time (strategy_id, created_at_ms)
) ENGINE=InnoDB COMMENT='OMS订单最新状态快照';

-- 订单事件只追加，用于审计、重建状态和故障分析。
CREATE TABLE oms_order_event (
    event_id VARCHAR(64) PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    order_status VARCHAR(30) NOT NULL,
    event_time BIGINT NOT NULL,
    fill_price DECIMAL(38,18) NULL,
    fill_quantity DECIMAL(38,18) NULL,
    reject_reason VARCHAR(50) NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_oms_event_order_time (order_id, event_time, create_time),
    INDEX idx_oms_event_type_time (event_type, event_time)
) ENGINE=InnoDB COMMENT='OMS订单状态事件';

-- 每次部分成交或完全成交单独记录，不能只保存订单平均成交价。
CREATE TABLE oms_fill (
    fill_id VARCHAR(64) PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL,
    event_id VARCHAR(64) NOT NULL,
    exchange_trade_id VARCHAR(100) NULL,
    fill_price DECIMAL(38,18) NOT NULL,
    fill_quantity DECIMAL(38,18) NOT NULL,
    fee DECIMAL(38,18) NOT NULL DEFAULT 0,
    fee_currency VARCHAR(20) NULL,
    liquidity_role VARCHAR(20) NULL COMMENT 'MAKER/TAKER/SIMULATED',
    fill_time BIGINT NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_oms_fill_event (event_id),
    INDEX idx_oms_fill_order_time (order_id, fill_time),
    INDEX idx_oms_exchange_trade (exchange_trade_id)
) ENGINE=InnoDB COMMENT='OMS逐笔成交明细';
