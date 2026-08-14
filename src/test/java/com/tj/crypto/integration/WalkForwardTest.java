package com.tj.crypto.integration;

import com.tj.crypto.backtest.engine.*;
import com.tj.crypto.backtest.report.PerformanceReport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Walk-forward 参数优化集成测试。
 * 使用 Binance 真实历史数据（7 天），验证 Walk-forward 优化的完整流程。
 *
 * 测试配置：7 天数据，2 天训练 + 1 天测试，共 3 个窗口。
 * 参数搜索范围：fast=[8,12], slow=[20,26], signal=[7,9]（共 8 组合）。
 *
 * 注意：此测试需要网络连接访问 Binance API（通过 SOCKS 代理）。
 */
@Tag("external")
class WalkForwardTest {

    private static final String SYMBOL = "BTCUSDT";
    private static final String TIMEFRAME_CODE = "1m";
    private static final int TOTAL_DAYS = 7;
    private static final int TRAIN_DAYS = 2;
    private static final int TEST_DAYS = 1;
    private static final double INITIAL_BALANCE = 100_000.0;

    // 参数搜索范围（小范围，加速测试）
    private static final int[] FAST_PERIODS = {8, 12};
    private static final int[] SLOW_PERIODS = {20, 26};
    private static final int[] SIGNAL_PERIODS = {7, 9};

    // 预期窗口数：floor(7 / (2+1)) = 2
    // 实际窗口数取决于可用数据量，至少 2 个
    private static final int MIN_EXPECTED_WINDOWS = 2;

    private static WalkForwardResult walkForwardResult;

    @BeforeAll
    @Timeout(value = 600, unit = TimeUnit.SECONDS)
    static void runWalkForwardOptimization() {
        WalkForwardOptimizer optimizer = new WalkForwardOptimizer();

        walkForwardResult = optimizer.optimize(
                SYMBOL, TIMEFRAME_CODE, TOTAL_DAYS, TRAIN_DAYS, TEST_DAYS,
                INITIAL_BALANCE, FAST_PERIODS, SLOW_PERIODS, SIGNAL_PERIODS);

        // 打印完整结果
        System.out.println(walkForwardResult.summary());
    }

    @Test
    @DisplayName("Walk-forward 结果不应为空")
    void shouldReturnNonEmptyResult() {
        assertThat(walkForwardResult)
                .as("Walk-forward result should not be null")
                .isNotNull();
        assertThat(walkForwardResult.windows())
                .as("Windows list should not be empty")
                .isNotEmpty();
    }

    @Test
    @DisplayName("窗口数量应正确（至少 2 个）")
    void shouldHaveCorrectWindowCount() {
        assertThat(walkForwardResult.windowCount())
                .as("Should have at least %d windows", MIN_EXPECTED_WINDOWS)
                .isGreaterThanOrEqualTo(MIN_EXPECTED_WINDOWS);
    }

    @Test
    @DisplayName("每个窗口应有最优参数")
    void shouldHaveBestParamsForEachWindow() {
        List<OptimizationResult> bestParams = walkForwardResult.bestParamsPerWindow();

        assertThat(bestParams)
                .as("Should have best params for each window")
                .hasSize(walkForwardResult.windowCount());

        for (int i = 0; i < bestParams.size(); i++) {
            OptimizationResult params = bestParams.get(i);
            assertThat(params)
                    .as("Window #%d should have non-null best params", i)
                    .isNotNull();
            assertThat(params.macdFast())
                    .as("Window #%d fast period should be in search range", i)
                    .isIn(FAST_PERIODS[0], FAST_PERIODS[1]);
            assertThat(params.macdSlow())
                    .as("Window #%d slow period should be in search range", i)
                    .isIn(SLOW_PERIODS[0], SLOW_PERIODS[1]);
            assertThat(params.macdSignal())
                    .as("Window #%d signal period should be in search range", i)
                    .isIn(SIGNAL_PERIODS[0], SIGNAL_PERIODS[1]);
            // fast < slow 约束
            assertThat(params.macdFast())
                    .as("Window #%d: fast(%d) should be < slow(%d)",
                            i, params.macdFast(), params.macdSlow())
                    .isLessThan(params.macdSlow());
        }
    }

    @Test
    @DisplayName("每个窗口的测试期结果应合理")
    void shouldHaveReasonableTestResultsForEachWindow() {
        List<WalkForwardWindow> windows = walkForwardResult.windows();

        for (WalkForwardWindow window : windows) {
            assertThat(window.testResult())
                    .as("Window #%d should have non-null test result", window.windowIndex())
                    .isNotNull();
            assertThat(window.testResult().performanceReport())
                    .as("Window #%d should have non-null performance report", window.windowIndex())
                    .isNotNull();

            // 测试期时间范围应合理
            assertThat(window.testStartTime())
                    .as("Window #%d test start should be after train end", window.windowIndex())
                    .isGreaterThanOrEqualTo(window.trainEndTime());
            assertThat(window.testEndTime())
                    .as("Window #%d test end should be after test start", window.windowIndex())
                    .isGreaterThan(window.testStartTime());
        }
    }

    @Test
    @DisplayName("组合结果应合理")
    void shouldHaveReasonableCombinedResults() {
        PerformanceReport report = walkForwardResult.combinedReport();

        assertThat(report)
                .as("Combined report should not be null")
                .isNotNull();

        // 总收益率：-100% 到 +1000%
        assertThat(report.totalReturn())
                .as("Combined total return should be between -100%% and +1000%%")
                .isGreaterThanOrEqualTo(BigDecimal.valueOf(-100))
                .isLessThanOrEqualTo(BigDecimal.valueOf(1000));

        // 最大回撤：0% 到 100%
        assertThat(report.maxDrawdown())
                .as("Combined max drawdown should be between 0%% and 100%%")
                .isGreaterThanOrEqualTo(BigDecimal.ZERO)
                .isLessThanOrEqualTo(BigDecimal.valueOf(100));

        // 胜率：0% 到 100%
        assertThat(report.winRate())
                .as("Combined win rate should be between 0%% and 100%%")
                .isGreaterThanOrEqualTo(BigDecimal.ZERO)
                .isLessThanOrEqualTo(BigDecimal.valueOf(100));

        // 交易次数 >= 0
        assertThat(report.totalTrades())
                .as("Combined total trades should be non-negative")
                .isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("组合交易记录应包含所有窗口的交易")
    void shouldCombineAllWindowTrades() {
        List<WalkForwardWindow> windows = walkForwardResult.windows();
        int expectedMinTrades = windows.stream()
                .mapToInt(w -> w.testResult().trades().size())
                .sum();

        assertThat(walkForwardResult.combinedTrades().size())
                .as("Combined trades should equal sum of all window trades")
                .isEqualTo(expectedMinTrades);
    }

    @Test
    @DisplayName("窗口时间范围应连续且不重叠")
    void shouldHaveNonOverlappingWindows() {
        List<WalkForwardWindow> windows = walkForwardResult.windows();

        for (int i = 0; i < windows.size() - 1; i++) {
            WalkForwardWindow current = windows.get(i);
            WalkForwardWindow next = windows.get(i + 1);

            assertThat(next.trainStartTime())
                    .as("Window #%d train start should be >= window #%d test end",
                            i + 1, i)
                    .isGreaterThanOrEqualTo(current.testEndTime());
        }
    }

    @Test
    @DisplayName("参数验证应拒绝无效输入")
    void shouldRejectInvalidInputs() {
        WalkForwardOptimizer optimizer = new WalkForwardOptimizer();

        // trainDays + testDays > totalDays
        assertThatThrownBy(() -> optimizer.optimize(
                SYMBOL, TIMEFRAME_CODE, 3, 2, 2, INITIAL_BALANCE,
                FAST_PERIODS, SLOW_PERIODS, SIGNAL_PERIODS))
                .as("Should reject when trainDays + testDays > totalDays")
                .isInstanceOf(IllegalArgumentException.class);

        // totalDays <= 0
        assertThatThrownBy(() -> optimizer.optimize(
                SYMBOL, TIMEFRAME_CODE, 0, 1, 1, INITIAL_BALANCE,
                FAST_PERIODS, SLOW_PERIODS, SIGNAL_PERIODS))
                .as("Should reject when totalDays <= 0")
                .isInstanceOf(IllegalArgumentException.class);

        // trainDays <= 0
        assertThatThrownBy(() -> optimizer.optimize(
                SYMBOL, TIMEFRAME_CODE, 7, 0, 1, INITIAL_BALANCE,
                FAST_PERIODS, SLOW_PERIODS, SIGNAL_PERIODS))
                .as("Should reject when trainDays <= 0")
                .isInstanceOf(IllegalArgumentException.class);

        // testDays <= 0
        assertThatThrownBy(() -> optimizer.optimize(
                SYMBOL, TIMEFRAME_CODE, 7, 2, 0, INITIAL_BALANCE,
                FAST_PERIODS, SLOW_PERIODS, SIGNAL_PERIODS))
                .as("Should reject when testDays <= 0")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("WalkForwardResult.summary() 应返回非空字符串")
    void shouldReturnNonEmptySummary() {
        String summary = walkForwardResult.summary();

        assertThat(summary)
                .as("Summary should not be null or blank")
                .isNotBlank();
        assertThat(summary)
                .as("Summary should contain 'Walk-Forward'")
                .contains("Walk-Forward");
    }
}
