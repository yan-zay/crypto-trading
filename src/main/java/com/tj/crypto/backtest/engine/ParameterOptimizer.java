package com.tj.crypto.backtest.engine;

import com.tj.crypto.backtest.data.HistoricalDataProvider;
import com.tj.crypto.backtest.report.PerformanceReport;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.factor.FactorProperties;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * MACD 参数网格搜索优化器。
 *
 * 对给定的 MACD 参数范围进行穷举搜索，运行每组参数的回测，
 * 按总收益率排序返回最优参数组合。
 *
 * 设计要点：
 * - 数据只获取一次，所有参数组合共用同一份历史数据
 * - 使用 CompletableFuture 并行运行回测（I/O 密集型）
 * - 结果按总收益率降序排列
 */
@Slf4j
public final class ParameterOptimizer {

    public ParameterOptimizer() {}

    /**
     * 运行 MACD 参数网格搜索优化。
     *
     * @param symbol         交易对符号，如 "BTCUSDT"
     * @param timeframeCode  时间周期代码，如 "1m", "5m"
     * @param daysBack       回测天数
     * @param initialBalance 初始资金
     * @param fastPeriods    MACD 快线周期搜索范围
     * @param slowPeriods    MACD 慢线周期搜索范围
     * @param signalPeriods  MACD 信号线周期搜索范围
     * @return 按总收益率降序排列的优化结果列表
     */
    public List<OptimizationResult> optimizeMacd(String symbol, String timeframeCode,
                                                  int daysBack, double initialBalance,
                                                  int[] fastPeriods, int[] slowPeriods,
                                                  int[] signalPeriods) {
        log.info("Starting MACD parameter optimization: {} {} {} days, params=[fast:{}, slow:{}, signal:{}]",
                symbol, timeframeCode, daysBack,
                formatArray(fastPeriods), formatArray(slowPeriods), formatArray(signalPeriods));

        // 1. 计算时间范围
        long[] timeRange = BacktestRunner.calculateTimeRange(timeframeCode, daysBack);
        long startTime = timeRange[0];
        long endTime = timeRange[1];

        // 2. 解析交易工具和时间周期
        Timeframe timeframe = Timeframe.fromCode(timeframeCode);
        Instrument instrument = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, symbol);

        // 3. 获取数据（只获取一次）
        log.info("Fetching historical data from Binance (one-time)...");
        HistoricalDataProvider dataProvider = BacktestRunner.createDataProvider();
        log.info("Data provider created successfully.");

        // 4. 生成所有参数组合
        List<int[]> combinations = generateCombinations(fastPeriods, slowPeriods, signalPeriods);
        log.info("Generated {} parameter combinations to test.", combinations.size());

        // 5. 并行运行回测
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(combinations.size(), Runtime.getRuntime().availableProcessors()));

        try {
            List<CompletableFuture<OptimizationResult>> futures = combinations.stream()
                    .map(params -> CompletableFuture.supplyAsync(() -> {
                        int fast = params[0];
                        int slow = params[1];
                        int signal = params[2];
                        return runSingleOptimization(instrument, timeframe, startTime, endTime,
                                initialBalance, fast, slow, signal, dataProvider);
                    }, executor))
                    .collect(Collectors.toList());

            // 6. 等待所有回测完成并收集结果
            List<OptimizationResult> results = futures.stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList());

            // 7. 按总收益率降序排列
            Collections.sort(results);

            log.info("Optimization complete. {} results generated.", results.size());
            return results;

        } finally {
            executor.shutdown();
        }
    }

    /**
     * 运行 MACD 参数网格搜索优化（指定时间范围和数据提供者）。
     * 用于 Walk-forward 优化等需要精确控制时间窗口的场景。
     *
     * @param instrument     交易工具
     * @param timeframe      时间周期
     * @param startTime      起始时间（毫秒）
     * @param endTime        结束时间（毫秒）
     * @param initialBalance 初始资金
     * @param fastPeriods    MACD 快线周期搜索范围
     * @param slowPeriods    MACD 慢线周期搜索范围
     * @param signalPeriods  MACD 信号线周期搜索范围
     * @param dataProvider   历史数据提供者（可复用）
     * @return 按总收益率降序排列的优化结果列表
     */
    public List<OptimizationResult> optimizeMacd(Instrument instrument, Timeframe timeframe,
                                                  long startTime, long endTime,
                                                  double initialBalance,
                                                  int[] fastPeriods, int[] slowPeriods,
                                                  int[] signalPeriods,
                                                  HistoricalDataProvider dataProvider) {
        log.info("Starting MACD parameter optimization: {} {} [{}, {}] initialBalance={}, params=[fast:{}, slow:{}, signal:{}]",
                instrument.symbol(), timeframe.getCode(), startTime, endTime, initialBalance,
                formatArray(fastPeriods), formatArray(slowPeriods), formatArray(signalPeriods));

        // 1. 生成所有参数组合
        List<int[]> combinations = generateCombinations(fastPeriods, slowPeriods, signalPeriods);
        log.info("Generated {} parameter combinations to test.", combinations.size());

        // 2. 并行运行回测
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(combinations.size(), Runtime.getRuntime().availableProcessors()));

        try {
            List<CompletableFuture<OptimizationResult>> futures = combinations.stream()
                    .map(params -> CompletableFuture.supplyAsync(() -> {
                        int fast = params[0];
                        int slow = params[1];
                        int signal = params[2];
                        return runSingleOptimization(instrument, timeframe, startTime, endTime,
                                initialBalance, fast, slow, signal, dataProvider);
                    }, executor))
                    .collect(Collectors.toList());

            // 3. 等待所有回测完成并收集结果
            List<OptimizationResult> results = futures.stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList());

            // 4. 按总收益率降序排列
            Collections.sort(results);

            log.info("Optimization complete. {} results generated.", results.size());
            return results;

        } finally {
            executor.shutdown();
        }
    }

    /**
     * 运行 MACD 参数网格搜索优化（返回 Top N）。
     *
     * @param symbol         交易对符号
     * @param timeframeCode  时间周期代码
     * @param daysBack       回测天数
     * @param initialBalance 初始资金
     * @param fastPeriods    MACD 快线周期搜索范围
     * @param slowPeriods    MACD 慢线周期搜索范围
     * @param signalPeriods  MACD 信号线周期搜索范围
     * @param topN           返回前 N 名结果
     * @return 按总收益率降序排列的 Top N 优化结果
     */
    public List<OptimizationResult> optimizeMacdTopN(String symbol, String timeframeCode,
                                                      int daysBack, double initialBalance,
                                                      int[] fastPeriods, int[] slowPeriods,
                                                      int[] signalPeriods, int topN) {
        List<OptimizationResult> allResults = optimizeMacd(
                symbol, timeframeCode, daysBack, initialBalance,
                fastPeriods, slowPeriods, signalPeriods);

        int limit = Math.min(topN, allResults.size());
        return allResults.subList(0, limit);
    }

    /**
     * 运行单组参数的回测。
     */
    private OptimizationResult runSingleOptimization(Instrument instrument, Timeframe timeframe,
                                                      long startTime, long endTime,
                                                      double initialBalance,
                                                      int fast, int slow, int signal,
                                                      HistoricalDataProvider dataProvider) {
        log.debug("Running backtest with MACD({}/{}/{})", fast, slow, signal);

        FactorProperties factorProperties = FactorProperties.customMacd(fast, slow, signal);

        BacktestResult result = BacktestRunner.runWithProvider(
                instrument, timeframe, startTime, endTime,
                initialBalance, factorProperties, dataProvider);

        PerformanceReport report = result.performanceReport();

        OptimizationResult optResult = new OptimizationResult(
                fast, slow, signal,
                report.totalReturn(),
                report.maxDrawdown(),
                report.winRate(),
                report.profitFactor(),
                report.totalTrades()
        );

        log.debug("MACD({}/{}/{}): return={}%, trades={}",
                fast, slow, signal, report.totalReturn(), report.totalTrades());

        return optResult;
    }

    /**
     * 生成所有参数组合（笛卡尔积）。
     */
    private List<int[]> generateCombinations(int[] fastPeriods, int[] slowPeriods, int[] signalPeriods) {
        List<int[]> combinations = new ArrayList<>();
        for (int fast : fastPeriods) {
            for (int slow : slowPeriods) {
                // 快线必须小于慢线才有意义
                if (fast >= slow) {
                    continue;
                }
                for (int signal : signalPeriods) {
                    combinations.add(new int[]{fast, slow, signal});
                }
            }
        }
        return combinations;
    }

    private String formatArray(int[] arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(arr[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
