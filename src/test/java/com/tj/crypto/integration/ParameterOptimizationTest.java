package com.tj.crypto.integration;

import com.tj.crypto.backtest.engine.OptimizationResult;
import com.tj.crypto.backtest.engine.OptimizationResultFormatter;
import com.tj.crypto.backtest.engine.ParameterOptimizer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MACD 参数网格搜索优化集成测试。
 * 使用 Binance 真实历史数据（3 天），验证参数优化器的完整流程。
 *
 * 测试参数范围：fast=[8,12], slow=[20,26], signal=[7,9]（共 8 组合）
 * 所有组合均满足 fast < slow 约束。
 *
 * 注意：此测试需要网络连接访问 Binance API（通过 SOCKS 代理）。
 */
@Tag("external")
class ParameterOptimizationTest {

    private static final String SYMBOL = "BTCUSDT";
    private static final String TIMEFRAME_CODE = "1m";
    private static final int DAYS_BACK = 3;
    private static final double INITIAL_BALANCE = 100_000.0;

    // 参数搜索范围
    private static final int[] FAST_PERIODS = {8, 12};
    private static final int[] SLOW_PERIODS = {20, 26};
    private static final int[] SIGNAL_PERIODS = {7, 9};

    // 预期组合数：2 * 2 * 2 = 8（所有组合均满足 fast < slow）
    private static final int EXPECTED_COMBINATIONS = 8;

    private static List<OptimizationResult> optimizationResults;

    @BeforeAll
    @Timeout(value = 600, unit = TimeUnit.SECONDS)
    static void runOptimization() {
        ParameterOptimizer optimizer = new ParameterOptimizer();

        optimizationResults = optimizer.optimizeMacd(
                SYMBOL, TIMEFRAME_CODE, DAYS_BACK, INITIAL_BALANCE,
                FAST_PERIODS, SLOW_PERIODS, SIGNAL_PERIODS);

        // 打印完整结果表格
        System.out.println(OptimizationResultFormatter.format(optimizationResults, SYMBOL));
        System.out.println(OptimizationResultFormatter.formatTopN(optimizationResults, 3));
    }

    @Test
    @DisplayName("优化结果不应为空")
    void shouldReturnNonEmptyResults() {
        assertThat(optimizationResults)
                .as("Optimization should return results")
                .isNotEmpty();
    }

    @Test
    @DisplayName("应返回预期数量的参数组合")
    void shouldReturnExpectedCombinationCount() {
        assertThat(optimizationResults)
                .as("Should return exactly %d combinations", EXPECTED_COMBINATIONS)
                .hasSize(EXPECTED_COMBINATIONS);
    }

    @Test
    @DisplayName("结果应按总收益率降序排列")
    void shouldBeSortedByTotalReturnDescending() {
        for (int i = 0; i < optimizationResults.size() - 1; i++) {
            BigDecimal current = optimizationResults.get(i).totalReturn();
            BigDecimal next = optimizationResults.get(i + 1).totalReturn();
            assertThat(current)
                    .as("Result at index %d (return=%s) should have return >= result at index %d (return=%s)",
                            i, current, i + 1, next)
                    .isGreaterThanOrEqualTo(next);
        }
    }

    @Test
    @DisplayName("所有结果的 MACD 参数应在搜索范围内")
    void shouldHaveValidParameters() {
        for (OptimizationResult result : optimizationResults) {
            assertThat(result.macdFast())
                    .as("MACD fast period should be in search range")
                    .isIn(java.util.Arrays.stream(FAST_PERIODS).boxed().toList());
            assertThat(result.macdSlow())
                    .as("MACD slow period should be in search range")
                    .isIn(java.util.Arrays.stream(SLOW_PERIODS).boxed().toList());
            assertThat(result.macdSignal())
                    .as("MACD signal period should be in search range")
                    .isIn(java.util.Arrays.stream(SIGNAL_PERIODS).boxed().toList());
            // fast < slow 约束
            assertThat(result.macdFast())
                    .as("MACD fast (%d) should be less than slow (%d)",
                            result.macdFast(), result.macdSlow())
                    .isLessThan(result.macdSlow());
        }
    }

    @Test
    @DisplayName("所有结果的性能指标应在合理范围内")
    void shouldHaveReasonablePerformanceMetrics() {
        for (OptimizationResult result : optimizationResults) {
            // 总收益率：-100% 到 +1000%
            assertThat(result.totalReturn())
                    .as("Total return for MACD(%s) should be between -100%% and +1000%%",
                            result.paramDescription())
                    .isGreaterThanOrEqualTo(BigDecimal.valueOf(-100))
                    .isLessThanOrEqualTo(BigDecimal.valueOf(1000));

            // 最大回撤：0% 到 100%
            assertThat(result.maxDrawdown())
                    .as("Max drawdown for MACD(%s) should be between 0%% and 100%%",
                            result.paramDescription())
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO)
                    .isLessThanOrEqualTo(BigDecimal.valueOf(100));

            // 胜率：0% 到 100%
            assertThat(result.winRate())
                    .as("Win rate for MACD(%s) should be between 0%% and 100%%",
                            result.paramDescription())
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO)
                    .isLessThanOrEqualTo(BigDecimal.valueOf(100));

            // 盈亏比 >= 0
            assertThat(result.profitFactor())
                    .as("Profit factor for MACD(%s) should be non-negative",
                            result.paramDescription())
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO);

            // 交易次数 >= 0
            assertThat(result.totalTrades())
                    .as("Total trades for MACD(%s) should be non-negative",
                            result.paramDescription())
                    .isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    @DisplayName("Top 1 参数应包含合理的 MACD 参数")
    void shouldHaveReasonableTopParameters() {
        OptimizationResult top1 = optimizationResults.get(0);

        // Top 1 的参数应在合理范围内
        assertThat(top1.macdFast())
                .as("Top 1 fast period should be reasonable (8-12)")
                .isGreaterThanOrEqualTo(8)
                .isLessThanOrEqualTo(12);
        assertThat(top1.macdSlow())
                .as("Top 1 slow period should be reasonable (20-26)")
                .isGreaterThanOrEqualTo(20)
                .isLessThanOrEqualTo(26);
        assertThat(top1.macdSignal())
                .as("Top 1 signal period should be reasonable (7-9)")
                .isGreaterThanOrEqualTo(7)
                .isLessThanOrEqualTo(9);

        // 打印 Top 1 详情
        System.out.printf("%nTop 1 MACD Parameters: %s%n", top1.paramDescription());
        System.out.printf("  Return: %s%%%n", top1.totalReturn());
        System.out.printf("  Max Drawdown: %s%%%n", top1.maxDrawdown());
        System.out.printf("  Win Rate: %s%%%n", top1.winRate());
        System.out.printf("  Profit Factor: %s%n", top1.profitFactor());
        System.out.printf("  Total Trades: %d%n", top1.totalTrades());
    }

    @Test
    @DisplayName("优化结果应包含所有预期的参数组合")
    void shouldContainAllExpectedCombinations() {
        // 验证所有 8 种组合都存在
        for (int fast : FAST_PERIODS) {
            for (int slow : SLOW_PERIODS) {
                if (fast >= slow) continue;
                for (int signal : SIGNAL_PERIODS) {
                    final int f = fast, s = slow, sig = signal;
                    boolean found = optimizationResults.stream()
                            .anyMatch(r -> r.macdFast() == f
                                    && r.macdSlow() == s
                                    && r.macdSignal() == sig);
                    assertThat(found)
                            .as("Should contain combination MACD(%d/%d/%d)", fast, slow, signal)
                            .isTrue();
                }
            }
        }
    }
}
