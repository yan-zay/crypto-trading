ALTER TABLE backtest_run
    ADD COLUMN strategy_config_json JSON NULL AFTER strategy_name,
    ADD COLUMN annualized_return_pct DECIMAL(24,12) NULL AFTER total_return_pct,
    ADD COLUMN sortino_ratio DECIMAL(24,12) NULL AFTER sharpe_ratio,
    ADD COLUMN calmar_ratio DECIMAL(24,12) NULL AFTER sortino_ratio,
    ADD COLUMN avg_trade_duration_ms DECIMAL(38,6) NULL AFTER calmar_ratio,
    ADD COLUMN max_win_streak INT NULL AFTER losing_trades,
    ADD COLUMN max_lose_streak INT NULL AFTER max_win_streak,
    ADD COLUMN signal_count INT NOT NULL DEFAULT 0 AFTER total_trades,
    ADD COLUMN monthly_returns_json JSON NULL AFTER total_fees;

CREATE TABLE backtest_equity_point (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    sequence_no INT NOT NULL,
    event_time BIGINT NOT NULL,
    equity DECIMAL(38,18) NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_backtest_equity_sequence (run_id, sequence_no),
    INDEX idx_backtest_equity_time (run_id, event_time)
) ENGINE=InnoDB COMMENT='Backtest mark-to-market equity curve';

CREATE TABLE backtest_signal (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    sequence_no INT NOT NULL,
    signal_time BIGINT NOT NULL,
    signal_type VARCHAR(16) NOT NULL,
    confidence DECIMAL(24,12) NOT NULL,
    reason VARCHAR(1000) NULL,
    factor_snapshot_json JSON NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_backtest_signal_sequence (run_id, sequence_no),
    INDEX idx_backtest_signal_time (run_id, signal_time)
) ENGINE=InnoDB COMMENT='Backtest signals and point-in-time factor snapshots';
