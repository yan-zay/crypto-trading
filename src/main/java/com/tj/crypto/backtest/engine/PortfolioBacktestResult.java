package com.tj.crypto.backtest.engine;

import com.tj.crypto.backtest.portfolio.Trade;
import com.tj.crypto.backtest.report.PerformanceReport;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 组合回测结果，不可变值对象。
 * 包含每个策略的独立回测结果和合并后的整体表现。
 *
 * @param perStrategyResults 每个策略的独立回测结果（key = 策略名称）
 * @param combinedTrades     所有策略的交易记录（按时间排序）
 * @param combinedReport     合并后的整体性能报告
 * @param allocationMap      资金分配比例（key = 策略名称, value = 分配百分比）
 */
public record PortfolioBacktestResult(
        Map<String, BacktestResult> perStrategyResults,
        List<Trade> combinedTrades,
        PerformanceReport combinedReport,
        Map<String, BigDecimal> allocationMap
) {
    /**
     * 获取指定策略的回测结果。
     *
     * @param strategyName 策略名称
     * @return 回测结果，不存在时返回 null
     */
    public BacktestResult getStrategyResult(String strategyName) {
        return perStrategyResults.get(strategyName);
    }

    /**
     * 获取所有策略名称。
     */
    public List<String> getStrategyNames() {
        return List.copyOf(perStrategyResults.keySet());
    }

    /**
     * 获取策略数量。
     */
    public int getStrategyCount() {
        return perStrategyResults.size();
    }

    /**
     * 获取组合总收益率（%）。
     */
    public BigDecimal getCombinedTotalReturn() {
        return combinedReport.totalReturn();
    }

    /**
     * 获取组合最大回撤（%）。
     */
    public BigDecimal getCombinedMaxDrawdown() {
        return combinedReport.maxDrawdown();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Portfolio Backtest Result [\n");
        sb.append(String.format("  Strategies: %d\n", getStrategyCount()));
        for (var entry : perStrategyResults.entrySet()) {
            String name = entry.getKey();
            BigDecimal alloc = allocationMap.getOrDefault(name, BigDecimal.ZERO);
            PerformanceReport report = entry.getValue().performanceReport();
            sb.append(String.format("  [%s] alloc=%s%% | %s\n", name, alloc, report));
        }
        sb.append(String.format("  Combined: %s\n", combinedReport));
        sb.append("]");
        return sb.toString();
    }
}
