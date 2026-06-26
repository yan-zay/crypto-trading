package com.tj.crypto.backtest.engine;

import com.tj.crypto.backtest.data.HistoricalDataProvider;
import com.tj.crypto.backtest.portfolio.Trade;
import com.tj.crypto.backtest.report.PerformanceCalculator;
import com.tj.crypto.backtest.report.PerformanceReport;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.factor.FactorProperties;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Walk-forward 参数优化器。
 *
 * 将历史数据切分为多个 train+test 窗口，对每个窗口：
 * 1. 训练期：运行参数网格搜索，选出最优参数
 * 2. 测试期：用最优参数做回测验证
 *
 * 最后合并所有测试期结果，评估参数的样本外表现。
 *
 * 设计要点：
 * - 数据只获取一次，所有窗口共用同一份历史数据
 * - 复用 ParameterOptimizer 进行训练期优化
 * - 复用 BacktestRunner.runWithProvider 进行测试期回测
 * - 性能计算使用 PerformanceCalculator
 */
@Slf4j
public final class WalkForwardOptimizer {

    private static final long MILLIS_PER_DAY = 86_400_000L;
    private static final int MACD_WARMUP_BARS = 40;

    private final ParameterOptimizer parameterOptimizer;
    private final PerformanceCalculator performanceCalculator;

    public WalkForwardOptimizer() {
        this.parameterOptimizer = new ParameterOptimizer();
        this.performanceCalculator = new PerformanceCalculator();
    }

    /**
     * 运行 Walk-forward 参数优化。
     *
     * @param symbol         交易对符号，如 "BTCUSDT"
     * @param timeframeCode  时间周期代码，如 "1m", "5m"
     * @param totalDays      总数据天数（含预热期）
     * @param trainDays      每个窗口的训练期天数
     * @param testDays       每个窗口的测试期天数
     * @param initialBalance 初始资金
     * @param fastPeriods    MACD 快线周期搜索范围
     * @param slowPeriods    MACD 慢线周期搜索范围
     * @param signalPeriods  MACD 信号线周期搜索范围
     * @return Walk-forward 优化结果
     */
    public WalkForwardResult optimize(String symbol, String timeframeCode,
                                       int totalDays, int trainDays, int testDays,
                                       double initialBalance,
                                       int[] fastPeriods, int[] slowPeriods,
                                       int[] signalPeriods) {
        validateInputs(totalDays, trainDays, testDays);

        Timeframe timeframe = Timeframe.fromCode(timeframeCode);
        Instrument instrument = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, symbol);

        // 1. 计算时间范围
        long[] timeRange = calculateTimeRange(timeframeCode, totalDays);
        long totalStart = timeRange[0];
        long totalEnd = timeRange[1];

        // 2. 获取数据（只获取一次）
        log.info("Walk-forward: fetching historical data for {} {} ({} days)...",
                symbol, timeframeCode, totalDays);
        HistoricalDataProvider dataProvider = BacktestRunner.createDataProvider();

        // 3. 计算窗口
        long windowMillis = (long) (trainDays + testDays) * MILLIS_PER_DAY;
        int windowCount = (int) ((totalEnd - totalStart) / windowMillis);
        if (windowCount < 1) {
            throw new IllegalArgumentException(
                    String.format("Not enough data for even one window: totalDays=%d, trainDays=%d, testDays=%d",
                            totalDays, trainDays, testDays));
        }

        log.info("Walk-forward: {} windows (train={}d, test={}d), time range [{}, {}]",
                windowCount, trainDays, testDays, totalStart, totalEnd);

        // 4. 逐窗口优化 + 测试
        List<WalkForwardWindow> windows = new ArrayList<>();
        List<Trade> allTestTrades = new ArrayList<>();

        for (int i = 0; i < windowCount; i++) {
            long trainStart = totalStart + (long) i * windowMillis;
            long trainEnd = trainStart + (long) trainDays * MILLIS_PER_DAY;
            long testStart = trainEnd;
            long testEnd = Math.min(testStart + (long) testDays * MILLIS_PER_DAY, totalEnd);

            if (testEnd <= testStart) {
                log.warn("Window #{}: test period is empty, skipping.", i);
                continue;
            }

            log.info("Window #{}: Train[{}, {}] Test[{}, {}]",
                    i, trainStart, trainEnd, testStart, testEnd);

            // 4a. 训练期优化
            List<OptimizationResult> trainResults = parameterOptimizer.optimizeMacd(
                    instrument, timeframe, trainStart, trainEnd,
                    initialBalance, fastPeriods, slowPeriods, signalPeriods,
                    dataProvider);

            if (trainResults.isEmpty()) {
                log.warn("Window #{}: no optimization results, skipping.", i);
                continue;
            }

            OptimizationResult bestParams = trainResults.get(0);
            log.info("Window #{}: best params MACD({}), train return={}%",
                    i, bestParams.paramDescription(), bestParams.totalReturn());

            // 4b. 测试期回测（使用训练期最优参数）
            FactorProperties testFactorProps = FactorProperties.customMacd(
                    bestParams.macdFast(), bestParams.macdSlow(), bestParams.macdSignal());

            BacktestResult testResult = BacktestRunner.runWithProvider(
                    instrument, timeframe, testStart, testEnd,
                    initialBalance, testFactorProps, dataProvider);

            log.info("Window #{}: test result - {} trades, {}",
                    i, testResult.trades().size(), testResult.performanceReport());

            // 4c. 记录窗口结果
            WalkForwardWindow window = new WalkForwardWindow(
                    i, trainStart, trainEnd, testStart, testEnd,
                    bestParams, testResult);
            windows.add(window);
            allTestTrades.addAll(testResult.trades());
        }

        // 5. 合并所有测试期结果
        allTestTrades.sort(Comparator.comparingLong(Trade::entryTime));

        PerformanceReport combinedReport = calculateCombinedReport(
                allTestTrades, initialBalance, totalStart, totalEnd);

        List<OptimizationResult> bestParamsPerWindow = windows.stream()
                .map(WalkForwardWindow::bestParams)
                .toList();

        WalkForwardResult result = new WalkForwardResult(
                windows, allTestTrades, combinedReport, bestParamsPerWindow);

        log.info("Walk-forward optimization complete.{}", result.summary());
        return result;
    }

    /**
     * 计算综合性能报告。
     * 将所有测试期交易合并，计算整体性能指标。
     */
    private PerformanceReport calculateCombinedReport(List<Trade> trades, double initialBalance,
                                                       long startTime, long endTime) {
        BigDecimal initial = BigDecimal.valueOf(initialBalance);
        BigDecimal totalPnL = trades.stream()
                .map(Trade::realizedPnL)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal finalBalance = initial.add(totalPnL);

        return performanceCalculator.calculate(
                trades, initial, finalBalance, startTime, endTime);
    }

    /**
     * 计算回测时间范围（含 MACD 预热期）。
     * 预热期在总时间范围之前，确保因子计算有足够历史数据。
     */
    private long[] calculateTimeRange(String timeframeCode, int totalDays) {
        Timeframe timeframe = Timeframe.fromCode(timeframeCode);
        long now = System.currentTimeMillis();
        long warmupMillis = MACD_WARMUP_BARS * timeframe.getMillis();
        long dataMillis = (long) totalDays * MILLIS_PER_DAY;
        long startTime = now - dataMillis - warmupMillis;
        long endTime = now - MILLIS_PER_DAY;
        return new long[]{startTime, endTime};
    }

    /**
     * 验证输入参数。
     */
    private void validateInputs(int totalDays, int trainDays, int testDays) {
        if (totalDays <= 0) {
            throw new IllegalArgumentException("totalDays must be positive: " + totalDays);
        }
        if (trainDays <= 0) {
            throw new IllegalArgumentException("trainDays must be positive: " + trainDays);
        }
        if (testDays <= 0) {
            throw new IllegalArgumentException("testDays must be positive: " + testDays);
        }
        if (trainDays + testDays > totalDays) {
            throw new IllegalArgumentException(
                    String.format("trainDays(%d) + testDays(%d) = %d exceeds totalDays(%d)",
                            trainDays, testDays, trainDays + testDays, totalDays));
        }
    }
}
