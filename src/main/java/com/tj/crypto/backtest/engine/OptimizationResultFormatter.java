package com.tj.crypto.backtest.engine;

import java.util.List;

/**
 * 优化结果格式化器。
 * 将 ParameterOptimizer 的输出格式化为可读的表格。
 */
public final class OptimizationResultFormatter {

    private OptimizationResultFormatter() {}

    /**
     * 格式化优化结果为表格字符串。
     *
     * @param results 优化结果列表（已按收益率排序）
     * @param symbol  交易对符号
     * @return 格式化的表格字符串
     */
    public static String format(List<OptimizationResult> results, String symbol) {
        if (results == null || results.isEmpty()) {
            return "No optimization results to display.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("========================================================================\n");
        sb.append("           MACD Parameter Optimization Results - ").append(symbol).append("\n");
        sb.append("========================================================================\n");
        sb.append(String.format("  %-4s  %-10s  %10s  %10s  %8s  %10s  %6s%n",
                "Rank", "MACD Params", "Return(%)", "MaxDD(%)", "WinRate", "PF", "Trades"));
        sb.append("------  ----------  ----------  ----------  --------  ----------  ------\n");

        for (int i = 0; i < results.size(); i++) {
            OptimizationResult r = results.get(i);
            sb.append(String.format("  %-4d  %-10s  %10s  %10s  %7s%%  %10s  %6d%n",
                    i + 1,
                    r.paramDescription(),
                    formatDecimal(r.totalReturn()),
                    formatDecimal(r.maxDrawdown()),
                    formatDecimal(r.winRate()),
                    formatDecimal(r.profitFactor()),
                    r.totalTrades()));
        }

        sb.append("========================================================================\n");
        sb.append(String.format("  Total combinations tested: %d%n", results.size()));
        sb.append("========================================================================\n");

        return sb.toString();
    }

    /**
     * 格式化 Top N 结果的简要摘要。
     *
     * @param results 优化结果列表（已按收益率排序）
     * @param topN    展示前 N 名
     * @return 简要摘要字符串
     */
    public static String formatTopN(List<OptimizationResult> results, int topN) {
        if (results == null || results.isEmpty()) {
            return "No optimization results.";
        }

        int limit = Math.min(topN, results.size());
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Top %d MACD Parameter Combinations:%n", limit));

        for (int i = 0; i < limit; i++) {
            OptimizationResult r = results.get(i);
            sb.append(String.format("  #%d: MACD(%s) Return=%s%% MaxDD=%s%% WinRate=%s%% PF=%s Trades=%d%n",
                    i + 1,
                    r.paramDescription(),
                    formatDecimal(r.totalReturn()),
                    formatDecimal(r.maxDrawdown()),
                    formatDecimal(r.winRate()),
                    formatDecimal(r.profitFactor()),
                    r.totalTrades()));
        }

        return sb.toString();
    }

    private static String formatDecimal(java.math.BigDecimal value) {
        if (value == null) return "N/A";
        return value.stripTrailingZeros().toPlainString();
    }
}
