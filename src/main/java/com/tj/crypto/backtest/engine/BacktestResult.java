package com.tj.crypto.backtest.engine;

import com.tj.crypto.backtest.portfolio.Trade;
import com.tj.crypto.backtest.report.PerformanceReport;
import com.tj.crypto.backtest.report.EquityPoint;
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
 * @param equityCurve       按市场事件时间记录的净值曲线
 * @param strategyName      本次运行的策略唯一名称
 * @param strategyConfigJson 本次运行的策略配置快照
 */
public record BacktestResult(
        BacktestConfig config,
        List<SignalEvent> signals,
        List<Trade> trades,
        PerformanceReport performanceReport,
        BigDecimal finalBalance,
        BacktestAssumptions assumptions,
        List<EquityPoint> equityCurve,
        String strategyName,
        String strategyConfigJson
) {
    public BacktestResult {
        equityCurve = equityCurve == null ? List.of() : List.copyOf(equityCurve);
        strategyName = strategyName == null || strategyName.isBlank() ? "UNKNOWN" : strategyName;
        strategyConfigJson = strategyConfigJson == null || strategyConfigJson.isBlank()
                ? "{}" : strategyConfigJson;
    }

    public BacktestResult(BacktestConfig config, List<SignalEvent> signals,
                          List<Trade> trades, PerformanceReport performanceReport,
                          BigDecimal finalBalance, BacktestAssumptions assumptions,
                          List<EquityPoint> equityCurve, String strategyName) {
        this(config, signals, trades, performanceReport, finalBalance, assumptions,
                equityCurve, strategyName, "{}");
    }

    public BacktestResult(BacktestConfig config, List<SignalEvent> signals,
                          List<Trade> trades, PerformanceReport performanceReport,
                          BigDecimal finalBalance, BacktestAssumptions assumptions,
                          List<EquityPoint> equityCurve) {
        this(config, signals, trades, performanceReport, finalBalance,
                assumptions, equityCurve, inferStrategyName(signals), "{}");
    }

    public BacktestResult(BacktestConfig config, List<SignalEvent> signals,
                          List<Trade> trades, PerformanceReport performanceReport,
                          BigDecimal finalBalance, BacktestAssumptions assumptions) {
        this(config, signals, trades, performanceReport, finalBalance, assumptions,
                List.of(), inferStrategyName(signals), "{}");
    }

    /**
     * 便捷构造函数（无假设，默认使用 defaults）。
     */
    public BacktestResult(BacktestConfig config, List<SignalEvent> signals,
                          List<Trade> trades, PerformanceReport performanceReport,
                          BigDecimal finalBalance) {
        this(config, signals, trades, performanceReport, finalBalance,
                BacktestAssumptions.defaults(), List.of(), inferStrategyName(signals), "{}");
    }

    private static String inferStrategyName(List<SignalEvent> signals) {
        return signals == null || signals.isEmpty() ? "UNKNOWN" : signals.get(0).strategyName();
    }
}
