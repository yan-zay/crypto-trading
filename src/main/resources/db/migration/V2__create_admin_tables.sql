-- 管理后台：策略配置表
CREATE TABLE IF NOT EXISTS strategy_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    strategy_name VARCHAR(50) NOT NULL COMMENT '策略名称',
    display_name VARCHAR(100) COMMENT '显示名称',
    description TEXT COMMENT '策略描述',
    enabled TINYINT(1) DEFAULT 1 COMMENT '是否启用',
    params JSON COMMENT '策略参数',
    symbols JSON COMMENT '监听交易对列表',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_strategy_name (strategy_name)
) ENGINE=InnoDB COMMENT='策略配置';

-- 管理后台：系统配置表
CREATE TABLE IF NOT EXISTS system_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_key VARCHAR(100) NOT NULL COMMENT '配置键',
    config_value TEXT COMMENT '配置值',
    config_type VARCHAR(20) DEFAULT 'STRING' COMMENT '配置类型(STRING/NUMBER/BOOLEAN/JSON)',
    description VARCHAR(255) COMMENT '配置说明',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_config_key (config_key)
) ENGINE=InnoDB COMMENT='系统配置';

-- 管理后台：操作日志表
CREATE TABLE IF NOT EXISTS operation_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operator VARCHAR(50) NOT NULL COMMENT '操作人',
    operation VARCHAR(50) NOT NULL COMMENT '操作类型',
    target_type VARCHAR(50) COMMENT '操作对象类型',
    target_id VARCHAR(100) COMMENT '操作对象ID',
    detail JSON COMMENT '操作详情',
    ip VARCHAR(50) COMMENT 'IP地址',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_operator_time (operator, created_at),
    INDEX idx_operation_time (operation, created_at)
) ENGINE=InnoDB COMMENT='操作日志';

-- 插入默认策略配置
INSERT IGNORE INTO strategy_config (strategy_name, display_name, description, enabled, params, symbols) VALUES
('LiquidationSpike', '爆仓突增策略', '检测大额爆仓事件，判断市场极端情绪', 1, '{"threshold-usd": 1000000}', '["BTCUSDT","ETHUSDT"]'),
('MacdCross', 'MACD交叉策略', 'MACD金叉死叉信号', 1, '{}', '["BTCUSDT","ETHUSDT"]'),
('RsiCross', 'RSI交叉策略', 'RSI超买超卖信号', 1, '{}', '["BTCUSDT","ETHUSDT"]'),
('BollingerBreakout', '布林带突破策略', '价格突破布林带信号', 1, '{}', '["BTCUSDT","ETHUSDT"]'),
('SuperTrend', '超级趋势策略', 'SuperTrend指标信号', 1, '{}', '["BTCUSDT","ETHUSDT"]'),
('AtrTrailingStop', 'ATR追踪止损策略', '基于ATR的追踪止损', 1, '{}', '["BTCUSDT","ETHUSDT"]');

-- 插入默认系统配置
INSERT IGNORE INTO system_config (config_key, config_value, config_type, description) VALUES
('risk.max-loss-per-trade-pct', '2.0', 'NUMBER', '单笔最大亏损百分比'),
('risk.max-daily-loss-pct', '5.0', 'NUMBER', '每日最大亏损百分比'),
('risk.max-size-pct', '30.0', 'NUMBER', '最大持仓百分比'),
('risk.slippage-bps', '5', 'NUMBER', '滑点(基点)'),
('system.notification.enabled', 'false', 'BOOLEAN', '是否开启通知'),
('system.notification.webhook-url', '', 'STRING', '通知Webhook地址');