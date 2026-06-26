package com.tj.crypto.backtest.engine;

import java.math.BigDecimal;

/**
 * 参数优化结果，不可变值对象。
 * 保存一组 MACD 参数及其对应的回测性能指标。
 *
 * @param macdFast     MACD 快线周期
 * @param macdSlow     MACD 慢线周期
 * @param macdSignal   MACD 信号线周期
 * @param totalReturn  总收益率 (%)
 * @param maxDrawdown  最大回撤 (%)
 * @param winRate      胜率 (%)
 * @param profitFactor 盈亏比
 * @param totalTrades  总交易次数
 */
public record OptimizationResult(
        int macdFast,
        int macdSlow,
        int macdSignal,
        BigDecimal totalReturn,
        BigDecimal maxDrawdown,
        BigDecimal winRate,
        BigDecimal profitFactor,
        int totalTrades
) implements Comparable<OptimizationResult> {

    /**
     * 按总收益率降序排列（收益率高的排在前面）。
     */
    @Override
    public int compareTo(OptimizationResult other) {
        return other.totalReturn.compareTo(this.totalReturn);
    }

    /**
     * 获取参数描述字符串，如 "12/26/9"。
     */
    public String paramDescription() {
        return macdFast + "/" + macdSlow + "/" + macdSignal;
    }
}
