-- K 线数据表
CREATE TABLE IF NOT EXISTS bar_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    exchange VARCHAR(20) NOT NULL COMMENT '交易所',
    market_type VARCHAR(20) NOT NULL COMMENT '市场类型',
    symbol VARCHAR(20) NOT NULL COMMENT '交易对',
    timeframe VARCHAR(5) NOT NULL COMMENT '时间周期',
    open_time BIGINT NOT NULL COMMENT 'K线开始时间(毫秒)',
    open_price DECIMAL(20,8) COMMENT '开盘价',
    high_price DECIMAL(20,8) COMMENT '最高价',
    low_price DECIMAL(20,8) COMMENT '最低价',
    close_price DECIMAL(20,8) COMMENT '收盘价',
    volume DECIMAL(20,8) COMMENT '成交量',
    quote_volume DECIMAL(20,8) COMMENT '成交额',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_symbol_tf_time (symbol, timeframe, open_time)
) ENGINE=InnoDB COMMENT='K线数据';

-- 策略信号表
CREATE TABLE IF NOT EXISTS signal_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    strategy_name VARCHAR(50) NOT NULL COMMENT '策略名称',
    exchange VARCHAR(20) NOT NULL COMMENT '交易所',
    symbol VARCHAR(20) NOT NULL COMMENT '交易对',
    signal_type VARCHAR(10) NOT NULL COMMENT '信号类型(BUY/SELL/HOLD)',
    confidence DECIMAL(5,4) COMMENT '置信度(0-1)',
    reason TEXT COMMENT '信号原因',
    factor_snapshot JSON COMMENT '因子快照',
    signal_time BIGINT NOT NULL COMMENT '信号时间(毫秒)',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_strategy_time (strategy_name, signal_time),
    INDEX idx_symbol_time (symbol, signal_time)
) ENGINE=InnoDB COMMENT='策略信号';

-- 交易记录表
CREATE TABLE IF NOT EXISTS trade_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    exchange VARCHAR(20) NOT NULL COMMENT '交易所',
    symbol VARCHAR(20) NOT NULL COMMENT '交易对',
    side VARCHAR(10) NOT NULL COMMENT '方向(LONG/SHORT)',
    quantity DECIMAL(20,8) COMMENT '数量',
    entry_price DECIMAL(20,8) COMMENT '开仓价格',
    exit_price DECIMAL(20,8) COMMENT '平仓价格',
    entry_time BIGINT NOT NULL COMMENT '开仓时间(毫秒)',
    exit_time BIGINT NOT NULL COMMENT '平仓时间(毫秒)',
    realized_pnl DECIMAL(20,8) COMMENT '已实现盈亏',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_symbol_time (symbol, exit_time)
) ENGINE=InnoDB COMMENT='交易记录';
