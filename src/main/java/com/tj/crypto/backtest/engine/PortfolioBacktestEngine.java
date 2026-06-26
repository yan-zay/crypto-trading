package com.tj.crypto.backtest.engine;

import com.tj.crypto.backtest.data.HistoricalDataProvider;
import com.tj.crypto.backtest.portfolio.Trade;
import com.tj.crypto.backtest.report.PerformanceCalculator;
import com.tj.crypto.backtest.report.PerformanceReport;
import com.tj.crypto.strategy.core.Strategy;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 组合回测引擎。
 * 对每个策略独立运行回测，然后合并所有策略的交易记录和性能报告。
 *
 * <p>设计要点：
 * <ul>
 *   <li>每个策略按分配比例获得独立的初始资金</li>
 *   <li>每个策略独立运行回测（独立的 VirtualAccount）</li>
 *   <li>交易记录合并后按时间排序</li>
 *   <li>合并的性能报告基于合并后的交易记录和总资金计算</li>
 * </ul>
 *
 * <p>典型用法：
 * <pre>
 *   PortfolioBacktestEngine engine = new PortfolioBacktestEngine(backtestEngine, performanceCalculator);
 *   PortfolioBacktestResult result = engine.run(config, strategies, allocations, dataProvider);
 * </pre>
 */
@Slf4j
public class PortfolioBacktestEngine {

    private static final int SCALE = 2;

    private final BacktestEngine backtestEngine;
    private final PerformanceCalculator performanceCalculator;

    public PortfolioBacktestEngine(BacktestEngine backtestEngine, PerformanceCalculator performanceCalculator) {
        this.backtestEngine = backtestEngine;
        this.performanceCalculator = performanceCalculator;
    }

    /**
     * 运行组合回测。
     *
     * @param config       回测配置（instrument、timeframe、startTime、endTime、initialBalance 为总资金）
     * @param strategies   策略列表
     * @param allocations  每个策略的资金分配比例（百分比），与 strategies 一一对应
     * @param dataProvider 历史数据提供者
     * @return 组合回测结果
     * @throws IllegalArgumentException 如果参数不合法
     */
    public PortfolioBacktestResult run(BacktestConfig config,
                                       List<Strategy> strategies,
                                       List<BigDecimal> allocations,
                                       HistoricalDataProvider dataProvider) {
        validateInputs(strategies, allocations);

        BigDecimal totalBalance = config.initialBalance();
        log.info("Starting portfolio backtest: {} strategies, total balance=${}",
                strategies.size(), totalBalance);

        // 1. 对每个策略独立运行回测
        Map<String, BacktestResult> perStrategyResults = new LinkedHashMap<>();
        Map<String, BigDecimal> allocationMap = new LinkedHashMap<>();
        List<Trade> allTrades = new ArrayList<>();
        BigDecimal combinedFinalBalance = BigDecimal.ZERO;

        for (int i = 0; i < strategies.size(); i++) {
            Strategy strategy = strategies.get(i);
            BigDecimal allocationPct = allocations.get(i);

            // 按比例分配初始资金
            BigDecimal strategyBalance = totalBalance.multiply(allocationPct)
                    .divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP);

            // 创建该策略专用的回测配置
            BacktestConfig strategyConfig = new BacktestConfig(
                    config.instrument(),
                    config.timeframe(),
                    config.startTime(),
                    config.endTime(),
                    strategyBalance
            );

            log.info("Running backtest for strategy '{}' with {}% allocation (balance=${})",
                    strategy.name(), allocationPct, strategyBalance);

            // 运行独立回测
            BacktestResult result = backtestEngine.run(strategyConfig, strategy, dataProvider);

            perStrategyResults.put(strategy.name(), result);
            allocationMap.put(strategy.name(), allocationPct);
            allTrades.addAll(result.trades());
            combinedFinalBalance = combinedFinalBalance.add(result.finalBalance());

            log.info("Strategy '{}': {} signals, {} trades, final balance=${}",
                    strategy.name(), result.signals().size(),
                    result.trades().size(), result.finalBalance());
        }

        // 2. 合并交易记录并按时间排序
        allTrades.sort(Comparator.comparingLong(Trade::entryTime));

        // 3. 计算合并后的性能报告
        PerformanceReport combinedReport = performanceCalculator.calculate(
                allTrades, totalBalance, combinedFinalBalance,
                config.startTime(), config.endTime());

        log.info("Portfolio backtest complete: {} total trades, combined return={}%",
                allTrades.size(), combinedReport.totalReturn());

        return new PortfolioBacktestResult(
                perStrategyResults, allTrades, combinedReport, allocationMap);
    }

    /**
     * 验证输入参数。
     */
    private void validateInputs(List<Strategy> strategies, List<BigDecimal> allocations) {
        if (strategies == null || strategies.isEmpty()) {
            throw new IllegalArgumentException("Strategies list must not be empty");
        }
        if (allocations == null || allocations.isEmpty()) {
            throw new IllegalArgumentException("Allocations list must not be empty");
        }
        if (strategies.size() != allocations.size()) {
            throw new IllegalArgumentException(
                    String.format("Strategies size (%d) must match allocations size (%d)",
                            strategies.size(), allocations.size()));
        }

        // 验证分配总和为 100%
        BigDecimal total = allocations.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal tolerance = BigDecimal.valueOf(0.01);
        if (total.subtract(BigDecimal.valueOf(100)).abs().compareTo(tolerance) > 0) {
            throw new IllegalArgumentException(
                    String.format("Total allocation must be 100%%, got %s%%", total));
        }

        // 验证每个分配为正数
        for (int i = 0; i < allocations.size(); i++) {
            if (allocations.get(i).compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException(
                        String.format("Allocation at index %d must be positive, got %s",
                                i, allocations.get(i)));
            }
        }
    }
}
