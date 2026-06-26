package com.tj.crypto.backtest.engine;

import com.tj.crypto.backtest.portfolio.Trade;
import com.tj.crypto.backtest.report.PerformanceReport;

import java.util.List;

/**
 * Walk-forward 优化结果，不可变值对象。
 *
 * @param windows             每个窗口的详细结果（训练 + 测试）
 * @param combinedTrades      所有测试期交易记录的合并（按时间排序）
 * @param combinedReport      基于合并交易计算的综合性能报告
 * @param bestParamsPerWindow 每个窗口训练期选出的最优参数
 */
public record WalkForwardResult(
        List<WalkForwardWindow> windows,
        List<Trade> combinedTrades,
        PerformanceReport combinedReport,
        List<OptimizationResult> bestParamsPerWindow
) {

    /**
     * 窗口数量。
     */
    public int windowCount() {
        return windows.size();
    }

    /**
     * 获取格式化的摘要字符串。
     */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n========================================================================\n");
        sb.append("           Walk-Forward Optimization Results\n");
        sb.append("========================================================================\n");
        sb.append(String.format("  Windows: %d%n", windowCount()));
        sb.append(String.format("  Combined Trades: %d%n", combinedTrades.size()));
        sb.append(String.format("  Combined Report: %s%n", combinedReport));
        sb.append("------------------------------------------------------------------------\n");

        for (WalkForwardWindow w : windows) {
            sb.append(String.format("  Window #%d: Train[%d - %d] Test[%d - %d]%n",
                    w.windowIndex() + 1, w.trainStartTime(), w.trainEndTime(),
                    w.testStartTime(), w.testEndTime()));
            sb.append(String.format("    Best Params: MACD(%s) Return=%s%%%n",
                    w.bestParams().paramDescription(), w.bestParams().totalReturn()));
            sb.append(String.format("    Test Result: %d trades, %s%n",
                    w.testResult().trades().size(), w.testResult().performanceReport()));
        }

        sb.append("========================================================================\n");
        return sb.toString();
    }
}
