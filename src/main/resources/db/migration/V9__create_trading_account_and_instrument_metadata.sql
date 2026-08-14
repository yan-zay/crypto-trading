CREATE TABLE instrument_metadata (
    metadata_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    exchange VARCHAR(20) NOT NULL,
    market_type VARCHAR(20) NOT NULL,
    symbol VARCHAR(40) NOT NULL,
    venue_symbol VARCHAR(60) NOT NULL,
    base_asset VARCHAR(20) NOT NULL,
    quote_asset VARCHAR(20) NOT NULL,
    settle_asset VARCHAR(20) NOT NULL,
    instrument_status VARCHAR(20) NOT NULL DEFAULT 'TRADING',
    tick_size DECIMAL(38,18) NOT NULL,
    step_size DECIMAL(38,18) NOT NULL,
    min_quantity DECIMAL(38,18) NOT NULL DEFAULT 0,
    max_quantity DECIMAL(38,18) NOT NULL DEFAULT 0,
    min_notional DECIMAL(38,18) NOT NULL DEFAULT 0,
    contract_multiplier DECIMAL(38,18) NOT NULL DEFAULT 1,
    maker_fee_rate DECIMAL(20,12) NOT NULL DEFAULT 0,
    taker_fee_rate DECIMAL(20,12) NOT NULL DEFAULT 0,
    max_leverage INT NOT NULL DEFAULT 1,
    maintenance_margin_rate DECIMAL(20,12) NOT NULL DEFAULT 0,
    funding_interval_hours INT NULL,
    valid_from_ms BIGINT NOT NULL,
    valid_to_ms BIGINT NULL,
    source_version VARCHAR(100) NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_instrument_metadata_version
        (exchange, market_type, symbol, valid_from_ms),
    INDEX idx_instrument_metadata_active
        (exchange, market_type, symbol, valid_to_ms)
) ENGINE=InnoDB COMMENT='按时间版本化的交易品种规则';

INSERT INTO instrument_metadata
    (exchange, market_type, symbol, venue_symbol, base_asset, quote_asset, settle_asset,
     tick_size, step_size, min_quantity, min_notional, contract_multiplier,
     maker_fee_rate, taker_fee_rate, max_leverage, maintenance_margin_rate,
     funding_interval_hours, valid_from_ms, source_version)
VALUES
    ('BINANCE','SPOT','BTCUSDT','BTCUSDT','BTC','USDT','USDT',0.01,0.00001,0.00001,5,1,0.001,0.001,1,0,NULL,0,'BOOTSTRAP_RESEARCH_V1'),
    ('BINANCE','SPOT','ETHUSDT','ETHUSDT','ETH','USDT','USDT',0.01,0.0001,0.0001,5,1,0.001,0.001,1,0,NULL,0,'BOOTSTRAP_RESEARCH_V1'),
    ('BINANCE','PERPETUAL','BTCUSDT','BTCUSDT','BTC','USDT','USDT',0.1,0.001,0.001,5,1,0.0002,0.0004,125,0.005,8,0,'BOOTSTRAP_RESEARCH_V1'),
    ('BINANCE','PERPETUAL','ETHUSDT','ETHUSDT','ETH','USDT','USDT',0.01,0.001,0.001,5,1,0.0002,0.0004,125,0.005,8,0,'BOOTSTRAP_RESEARCH_V1'),
    ('OKX','SPOT','BTCUSDT','BTC-USDT','BTC','USDT','USDT',0.1,0.00000001,0.00001,5,1,0.0008,0.001,1,0,NULL,0,'BOOTSTRAP_RESEARCH_V1'),
    ('OKX','SPOT','ETHUSDT','ETH-USDT','ETH','USDT','USDT',0.01,0.00000001,0.0001,5,1,0.0008,0.001,1,0,NULL,0,'BOOTSTRAP_RESEARCH_V1'),
    ('OKX','PERPETUAL','BTCUSDT','BTC-USDT-SWAP','BTC','USDT','USDT',0.1,0.01,0.01,5,0.01,0.0002,0.0005,100,0.005,8,0,'BOOTSTRAP_RESEARCH_V1'),
    ('OKX','PERPETUAL','ETHUSDT','ETH-USDT-SWAP','ETH','USDT','USDT',0.01,0.1,0.1,5,0.01,0.0002,0.0005,100,0.005,8,0,'BOOTSTRAP_RESEARCH_V1'),
    ('COINGLASS','SPOT','BTCUSDT','BTCUSDT','BTC','USDT','USDT',0.01,0.00001,0.00001,5,1,0,0,1,0,NULL,0,'NON_EXECUTABLE_DATA_SOURCE_V1'),
    ('COINGLASS','SPOT','ETHUSDT','ETHUSDT','ETH','USDT','USDT',0.01,0.0001,0.0001,5,1,0,0,1,0,NULL,0,'NON_EXECUTABLE_DATA_SOURCE_V1'),
    ('COINGLASS','PERPETUAL','BTCUSDT','BTCUSDT','BTC','USDT','USDT',0.1,0.001,0.001,5,1,0,0,1,0.005,8,0,'NON_EXECUTABLE_DATA_SOURCE_V1'),
    ('COINGLASS','PERPETUAL','ETHUSDT','ETHUSDT','ETH','USDT','USDT',0.01,0.001,0.001,5,1,0,0,1,0.005,8,0,'NON_EXECUTABLE_DATA_SOURCE_V1');

UPDATE instrument_metadata
SET instrument_status='DATA_ONLY'
WHERE exchange='COINGLASS';

CREATE TABLE paper_account (
    account_id VARCHAR(64) PRIMARY KEY,
    account_name VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    base_currency VARCHAR(20) NOT NULL DEFAULT 'USDT',
    initial_balance DECIMAL(38,18) NOT NULL,
    started_at_ms BIGINT NOT NULL,
    stopped_at_ms BIGINT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_paper_account_status_time (status, started_at_ms)
) ENGINE=InnoDB COMMENT='可恢复的模拟交易账户';

CREATE TABLE paper_balance (
    account_id VARCHAR(64) NOT NULL,
    asset VARCHAR(20) NOT NULL,
    total_balance DECIMAL(38,18) NOT NULL,
    available_balance DECIMAL(38,18) NOT NULL,
    locked_balance DECIMAL(38,18) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (account_id, asset)
) ENGINE=InnoDB COMMENT='模拟账户资产余额快照';

CREATE TABLE paper_position (
    position_id VARCHAR(64) PRIMARY KEY,
    account_id VARCHAR(64) NOT NULL,
    exchange VARCHAR(20) NOT NULL,
    market_type VARCHAR(20) NOT NULL,
    symbol VARCHAR(40) NOT NULL,
    side VARCHAR(8) NOT NULL,
    quantity DECIMAL(38,18) NOT NULL,
    entry_price DECIMAL(38,18) NOT NULL,
    mark_price DECIMAL(38,18) NOT NULL,
    contract_multiplier DECIMAL(38,18) NOT NULL DEFAULT 1,
    leverage INT NOT NULL DEFAULT 1,
    margin_mode VARCHAR(20) NOT NULL DEFAULT 'ISOLATED',
    initial_margin DECIMAL(38,18) NOT NULL DEFAULT 0,
    maintenance_margin DECIMAL(38,18) NOT NULL DEFAULT 0,
    open_fee DECIMAL(38,18) NOT NULL DEFAULT 0,
    funding DECIMAL(38,18) NOT NULL DEFAULT 0,
    realized_pnl DECIMAL(38,18) NOT NULL DEFAULT 0,
    unrealized_pnl DECIMAL(38,18) NOT NULL DEFAULT 0,
    strategy_id VARCHAR(100) NOT NULL,
    open_order_id VARCHAR(64) NOT NULL,
    opened_at_ms BIGINT NOT NULL,
    updated_at_ms BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_paper_position_instrument (account_id, exchange, market_type, symbol),
    INDEX idx_paper_position_account (account_id, updated_at_ms)
) ENGINE=InnoDB COMMENT='模拟账户持仓快照';

CREATE TABLE paper_mark_price (
    exchange VARCHAR(20) NOT NULL,
    market_type VARCHAR(20) NOT NULL,
    symbol VARCHAR(40) NOT NULL,
    price DECIMAL(38,18) NOT NULL,
    high_price DECIMAL(38,18) NOT NULL,
    low_price DECIMAL(38,18) NOT NULL,
    base_volume DECIMAL(38,18) NOT NULL DEFAULT 0,
    event_time_ms BIGINT NOT NULL,
    source VARCHAR(30) NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (exchange, market_type, symbol)
) ENGINE=InnoDB COMMENT='模拟撮合最近市场快照';

CREATE TABLE paper_order_reservation (
    order_id VARCHAR(64) PRIMARY KEY,
    account_id VARCHAR(64) NOT NULL,
    asset VARCHAR(20) NOT NULL,
    reservation_type VARCHAR(30) NOT NULL,
    original_amount DECIMAL(38,18) NOT NULL,
    remaining_amount DECIMAL(38,18) NOT NULL,
    original_quantity DECIMAL(38,18) NOT NULL,
    remaining_quantity DECIMAL(38,18) NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_paper_reservation_account (account_id, update_time)
) ENGINE=InnoDB COMMENT='活跃模拟订单冻结资金或资产';

CREATE TABLE account_ledger_transaction (
    transaction_id VARCHAR(64) PRIMARY KEY,
    account_id VARCHAR(64) NOT NULL,
    transaction_type VARCHAR(40) NOT NULL,
    reference_type VARCHAR(40) NOT NULL,
    reference_id VARCHAR(100) NOT NULL,
    event_time_ms BIGINT NOT NULL,
    description VARCHAR(500) NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ledger_reference (account_id, transaction_type, reference_id),
    INDEX idx_ledger_transaction_account_time (account_id, event_time_ms)
) ENGINE=InnoDB COMMENT='双分录交易头';

CREATE TABLE account_ledger_entry (
    entry_id VARCHAR(64) PRIMARY KEY,
    transaction_id VARCHAR(64) NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    ledger_account VARCHAR(40) NOT NULL,
    asset VARCHAR(20) NOT NULL,
    debit DECIMAL(38,18) NOT NULL DEFAULT 0,
    credit DECIMAL(38,18) NOT NULL DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ledger_entry_transaction (transaction_id),
    INDEX idx_ledger_entry_account_asset (account_id, asset, ledger_account)
) ENGINE=InnoDB COMMENT='双分录明细';

CREATE TABLE paper_trade (
    trade_id VARCHAR(64) PRIMARY KEY,
    account_id VARCHAR(64) NOT NULL,
    strategy_id VARCHAR(100) NOT NULL,
    exchange VARCHAR(20) NOT NULL,
    market_type VARCHAR(20) NOT NULL,
    symbol VARCHAR(40) NOT NULL,
    side VARCHAR(8) NOT NULL,
    quantity DECIMAL(38,18) NOT NULL,
    entry_price DECIMAL(38,18) NOT NULL,
    exit_price DECIMAL(38,18) NOT NULL,
    gross_pnl DECIMAL(38,18) NOT NULL,
    open_fee DECIMAL(38,18) NOT NULL DEFAULT 0,
    close_fee DECIMAL(38,18) NOT NULL DEFAULT 0,
    funding DECIMAL(38,18) NOT NULL DEFAULT 0,
    net_pnl DECIMAL(38,18) NOT NULL,
    open_order_id VARCHAR(64) NOT NULL,
    close_order_id VARCHAR(64) NOT NULL,
    opened_at_ms BIGINT NOT NULL,
    closed_at_ms BIGINT NOT NULL,
    duration_ms BIGINT NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_paper_trade_account_time (account_id, closed_at_ms),
    INDEX idx_paper_trade_strategy_time (strategy_id, closed_at_ms),
    INDEX idx_paper_trade_instrument_time (exchange, market_type, symbol, closed_at_ms)
) ENGINE=InnoDB COMMENT='模拟盘已平仓交易与归因事实';

CREATE TABLE paper_equity_snapshot (
    snapshot_id VARCHAR(64) PRIMARY KEY,
    account_id VARCHAR(64) NOT NULL,
    event_time_ms BIGINT NOT NULL,
    balance DECIMAL(38,18) NOT NULL,
    available_balance DECIMAL(38,18) NOT NULL,
    locked_margin DECIMAL(38,18) NOT NULL,
    unrealized_pnl DECIMAL(38,18) NOT NULL,
    equity DECIMAL(38,18) NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_paper_equity_account_time (account_id, event_time_ms)
) ENGINE=InnoDB COMMENT='模拟账户权益曲线';

CREATE TABLE paper_funding_settlement (
    funding_event_id VARCHAR(100) PRIMARY KEY,
    account_id VARCHAR(64) NOT NULL,
    position_id VARCHAR(64) NOT NULL,
    exchange VARCHAR(20) NOT NULL,
    symbol VARCHAR(40) NOT NULL,
    funding_rate DECIMAL(20,12) NOT NULL,
    mark_price DECIMAL(38,18) NOT NULL,
    funding_amount DECIMAL(38,18) NOT NULL,
    event_time_ms BIGINT NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_paper_funding_account_time (account_id, event_time_ms)
) ENGINE=InnoDB COMMENT='幂等的永续资金费率结算';

ALTER TABLE oms_order
    ADD COLUMN account_id VARCHAR(64) NULL AFTER client_order_id,
    ADD COLUMN order_source VARCHAR(20) NOT NULL DEFAULT 'LEGACY' AFTER account_id,
    ADD COLUMN venue_order_id VARCHAR(100) NULL AFTER order_source,
    ADD COLUMN external_status VARCHAR(50) NULL AFTER venue_order_id,
    ADD COLUMN correlation_id VARCHAR(64) NULL AFTER external_status,
    ADD COLUMN leverage INT NOT NULL DEFAULT 1 AFTER correlation_id,
    ADD COLUMN margin_mode VARCHAR(20) NOT NULL DEFAULT 'ISOLATED' AFTER leverage,
    ADD COLUMN state_version BIGINT NOT NULL DEFAULT 0 AFTER margin_mode,
    ADD COLUMN last_event_at_ms BIGINT NULL AFTER state_version,
    ADD INDEX idx_oms_account_status_time (account_id, status, created_at_ms),
    ADD INDEX idx_oms_venue_order (exchange, venue_order_id);

ALTER TABLE oms_order_event
    ADD COLUMN external_event_id VARCHAR(100) NULL AFTER event_id,
    ADD COLUMN payload_checksum VARCHAR(64) NULL AFTER external_event_id,
    ADD UNIQUE KEY uk_oms_external_event (external_event_id);

ALTER TABLE oms_fill
    ADD COLUMN account_id VARCHAR(64) NULL AFTER fill_id,
    ADD COLUMN strategy_id VARCHAR(100) NULL AFTER account_id,
    ADD COLUMN reference_price DECIMAL(38,18) NULL AFTER fill_quantity,
    ADD COLUMN arrival_price DECIMAL(38,18) NULL AFTER reference_price,
    ADD COLUMN spread_bps DECIMAL(20,8) NOT NULL DEFAULT 0 AFTER arrival_price,
    ADD COLUMN impact_bps DECIMAL(20,8) NOT NULL DEFAULT 0 AFTER spread_bps,
    ADD COLUMN slippage_bps DECIMAL(20,8) NOT NULL DEFAULT 0 AFTER impact_bps,
    ADD INDEX idx_oms_fill_account_time (account_id, fill_time);
