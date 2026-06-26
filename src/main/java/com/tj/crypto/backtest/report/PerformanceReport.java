package com.tj.crypto.backtest.report;

import java.math.BigDecimal;

/**
 * 性能报告，不可变值对象。
 *
 * @param totalReturn          总收益率 (%)
 * @param maxDrawdown          最大回撤 (%)
 * @param winRate              胜率 (%)
 * @param totalTrades          总交易次数
 * @param winningTrades        盈利次数
 * @param losingTrades         亏损次数
 * @param avgWin               平均盈利
 * @param avgLoss              平均亏损
 * @param profitFactor         盈亏比（总盈利/总亏损）
 * @param maxConsecutiveLosses 最大连续亏损次数
 * @param initialBalance       初始资金
 * @param finalBalance         最终资金
 * @param startTime            回测起始时间
 * @param endTime              回测结束时间
 */
public record PerformanceReport(
        BigDecimal totalReturn,
        BigDecimal maxDrawdown,
        BigDecimal winRate,
        int totalTrades,
        int winningTrades,
        int losingTrades,
        BigDecimal avgWin,
        BigDecimal avgLoss,
        BigDecimal profitFactor,
        int maxConsecutiveLosses,
        BigDecimal initialBalance,
        BigDecimal finalBalance,
        long startTime,
        long endTime
) {
    @Override
    public String toString() {
        return String.format(
                "Performance Report [%.2f%% return, %.2f%% maxDD, %.1f%% winRate, %d trades, PF=%.2f]",
                totalReturn, maxDrawdown, winRate, totalTrades, profitFactor);
    }
}
