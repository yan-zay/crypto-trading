package com.tj.crypto.backtest.engine;

import com.tj.crypto.backtest.portfolio.Trade;
import com.tj.crypto.backtest.report.PerformanceReport;
import com.tj.crypto.strategy.core.SignalEvent;

import java.math.BigDecimal;
import java.util.List;

/**
 * 回测结果，不可变值对象。
 *
 * @param config            回测配置
 * @param signals           策略产生的所有信号
 * @param trades            所有交易记录
 * @param performanceReport 性能报告
 * @param finalBalance      最终余额
 * @param assumptions       回测假设快照
 */
public record BacktestResult(
        BacktestConfig config,
        List<SignalEvent> signals,
        List<Trade> trades,
        PerformanceReport performanceReport,
        BigDecimal finalBalance,
        BacktestAssumptions assumptions
) {
    /**
     * 便捷构造函数（无假设，默认使用 defaults）。
     */
    public BacktestResult(BacktestConfig config, List<SignalEvent> signals,
                          List<Trade> trades, PerformanceReport performanceReport,
                          BigDecimal finalBalance) {
        this(config, signals, trades, performanceReport, finalBalance, BacktestAssumptions.defaults());
    }
}
