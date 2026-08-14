package com.tj.crypto.integration;

import com.tj.crypto.backtest.data.CsvHistoricalDataProvider;
import com.tj.crypto.backtest.engine.BacktestConfig;
import com.tj.crypto.backtest.engine.BacktestEngine;
import com.tj.crypto.backtest.engine.BacktestResult;
import com.tj.crypto.backtest.engine.PortfolioBacktestEngine;
import com.tj.crypto.backtest.engine.PortfolioBacktestResult;
import com.tj.crypto.backtest.portfolio.Trade;
import com.tj.crypto.backtest.report.PerformanceCalculator;
import com.tj.crypto.backtest.report.PerformanceReport;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.event.InMemoryEventBus;
import com.tj.crypto.execution.ExecutionEngine;
import com.tj.crypto.execution.FixedSlippageModel;
import com.tj.crypto.factor.FactorProperties;
import com.tj.crypto.factor.cache.InMemoryBarCache;
import com.tj.crypto.factor.core.FactorCalculator;
import com.tj.crypto.factor.technical.MacdFactor;
import com.tj.crypto.factor.technical.RsiFactor;
import com.tj.crypto.risk.PositionSizer;
import com.tj.crypto.risk.RiskEngine;
import com.tj.crypto.risk.RiskProperties;
import com.tj.crypto.strategy.core.Strategy;
import com.tj.crypto.strategy.impl.MacdCrossStrategy;
import com.tj.crypto.strategy.impl.RsiCrossStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 组合回测集成测试。
 * 测试 MACD + RSI 双策略组合回测的完整流程。
 *
 * <p>测试内容：
 * <ul>
 *   <li>组合结果非空</li>
 *   <li>每个策略独立结果存在</li>
 *   <li>资金分配正确</li>
 *   <li>合并交易记录按时间排序</li>
 *   <li>合并性能报告有效</li>
 * </ul>
 */
class PortfolioBacktestTest {

    private static final BigDecimal INITIAL_BALANCE = BigDecimal.valueOf(100_000);
    private static final BigDecimal MACD_ALLOCATION = BigDecimal.valueOf(60);
    private static final BigDecimal RSI_ALLOCATION = BigDecimal.valueOf(40);

    private PortfolioBacktestEngine portfolioEngine;
    private Instrument btcUsdt;
    private BacktestConfig config;
    private CsvHistoricalDataProvider dataProvider;
    private Strategy macdStrategy;
    private Strategy rsiStrategy;

    @BeforeEach
    void setUp() throws URISyntaxException {
        // 1. 创建共享 BarCache 和因子计算器
        InMemoryEventBus sharedEventBus = new InMemoryEventBus();
        InMemoryBarCache sharedBarCache = new InMemoryBarCache(sharedEventBus);
        FactorProperties factorProperties = new FactorProperties();

        MacdFactor macdFactor = new MacdFactor(sharedBarCache, factorProperties);
        RsiFactor rsiFactor = new RsiFactor(sharedBarCache, factorProperties);
        List<FactorCalculator> factorCalculators = List.of(macdFactor, rsiFactor);

        // 2. 创建执行引擎（无风控规则，简化测试）
        PerformanceCalculator performanceCalculator = new PerformanceCalculator();
        RiskProperties riskProperties = new RiskProperties();
        ExecutionEngine executionEngine = new ExecutionEngine(
                new RiskEngine(List.of()), new PositionSizer(riskProperties), new FixedSlippageModel(riskProperties),
                new com.tj.crypto.risk.KillSwitch());

        // 3. 创建回测引擎和组合回测引擎
        BacktestEngine backtestEngine = new BacktestEngine(performanceCalculator, factorCalculators, executionEngine);
        portfolioEngine = new PortfolioBacktestEngine(backtestEngine, performanceCalculator);

        // 4. 加载 CSV 测试数据
        btcUsdt = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
        long startTime = 1_700_000_000_000L;
        long endTime = 1_700_005_940_000L;
        config = new BacktestConfig(btcUsdt, Timeframe.M1, startTime, endTime, INITIAL_BALANCE);

        Path csvPath = Paths.get(Objects.requireNonNull(
                getClass().getClassLoader().getResource("backtest/btcusdt_1m_sample.csv")).toURI());
        dataProvider = new CsvHistoricalDataProvider(csvPath, Exchange.BINANCE, MarketType.PERPETUAL);

        // 5. 创建策略
        macdStrategy = new MacdCrossStrategy();
        rsiStrategy = new RsiCrossStrategy();
    }

    @Test
    @DisplayName("组合回测结果不应为空")
    void shouldReturnNonNullPortfolioResult() {
        PortfolioBacktestResult result = runPortfolioBacktest();

        assertThat(result).isNotNull();
        assertThat(result.combinedReport()).isNotNull();
        assertThat(result.combinedTrades()).isNotNull();
        assertThat(result.perStrategyResults()).isNotNull();
    }

    @Test
    @DisplayName("每个策略应有独立的回测结果")
    void shouldHavePerStrategyResults() {
        PortfolioBacktestResult result = runPortfolioBacktest();

        // 验证每个策略都有结果
        assertThat(result.perStrategyResults()).containsKey("MacdCross");
        assertThat(result.perStrategyResults()).containsKey("RsiCross");
        assertThat(result.getStrategyCount()).isEqualTo(2);

        // 验证每个策略结果独立
        BacktestResult macdResult = result.getStrategyResult("MacdCross");
        BacktestResult rsiResult = result.getStrategyResult("RsiCross");

        assertThat(macdResult).isNotNull();
        assertThat(rsiResult).isNotNull();
        assertThat(macdResult.config()).isNotNull();
        assertThat(rsiResult.config()).isNotNull();
    }

    @Test
    @DisplayName("资金分配应正确")
    void shouldHaveCorrectAllocation() {
        PortfolioBacktestResult result = runPortfolioBacktest();

        Map<String, BigDecimal> allocationMap = result.allocationMap();
        assertThat(allocationMap).containsEntry("MacdCross", MACD_ALLOCATION);
        assertThat(allocationMap).containsEntry("RsiCross", RSI_ALLOCATION);

        // 验证分配总和为 100%
        BigDecimal totalAllocation = allocationMap.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalAllocation).isEqualByComparingTo(BigDecimal.valueOf(100));
    }

    @Test
    @DisplayName("每个策略的初始资金应按比例分配")
    void shouldAllocateInitialBalanceProportionally() {
        PortfolioBacktestResult result = runPortfolioBacktest();

        BacktestResult macdResult = result.getStrategyResult("MacdCross");
        BacktestResult rsiResult = result.getStrategyResult("RsiCross");

        // MACD 策略应获得 60% 的资金
        BigDecimal expectedMacdBalance = INITIAL_BALANCE.multiply(MACD_ALLOCATION)
                .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
        assertThat(macdResult.config().initialBalance())
                .isEqualByComparingTo(expectedMacdBalance);

        // RSI 策略应获得 40% 的资金
        BigDecimal expectedRsiBalance = INITIAL_BALANCE.multiply(RSI_ALLOCATION)
                .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
        assertThat(rsiResult.config().initialBalance())
                .isEqualByComparingTo(expectedRsiBalance);
    }

    @Test
    @DisplayName("合并交易记录应按时间排序")
    void shouldSortCombinedTradesByTime() {
        PortfolioBacktestResult result = runPortfolioBacktest();

        List<Trade> combinedTrades = result.combinedTrades();
        if (combinedTrades.size() > 1) {
            for (int i = 1; i < combinedTrades.size(); i++) {
                assertThat(combinedTrades.get(i).entryTime())
                        .isGreaterThanOrEqualTo(combinedTrades.get(i - 1).entryTime());
            }
        }
    }

    @Test
    @DisplayName("合并交易记录应包含所有策略的交易")
    void shouldCombineAllStrategyTrades() {
        PortfolioBacktestResult result = runPortfolioBacktest();

        int macdTradeCount = result.getStrategyResult("MacdCross").trades().size();
        int rsiTradeCount = result.getStrategyResult("RsiCross").trades().size();
        int combinedTradeCount = result.combinedTrades().size();

        assertThat(combinedTradeCount).isEqualTo(macdTradeCount + rsiTradeCount);
    }

    @Test
    @DisplayName("合并性能报告应反映组合整体表现")
    void shouldGenerateCombinedPerformanceReport() {
        PortfolioBacktestResult result = runPortfolioBacktest();

        PerformanceReport report = result.combinedReport();
        assertThat(report).isNotNull();
        assertThat(report.initialBalance()).isEqualByComparingTo(INITIAL_BALANCE);
        assertThat(report.startTime()).isEqualTo(config.startTime());
        assertThat(report.endTime()).isEqualTo(config.endTime());

        // 合并后的交易统计应与合并交易数一致
        assertThat(report.totalTrades()).isEqualTo(result.combinedTrades().size());
        assertThat(report.totalTrades())
                .isEqualTo(report.winningTrades() + report.losingTrades());
    }

    @Test
    @DisplayName("合并最终余额应等于各策略最终余额之和")
    void shouldSumFinalBalances() {
        PortfolioBacktestResult result = runPortfolioBacktest();

        BigDecimal macdFinal = result.getStrategyResult("MacdCross").finalBalance();
        BigDecimal rsiFinal = result.getStrategyResult("RsiCross").finalBalance();
        BigDecimal combinedFinal = result.combinedReport().finalBalance();

        assertThat(combinedFinal).isEqualByComparingTo(macdFinal.add(rsiFinal));
    }

    @Test
    @DisplayName("组合结果 toString 应包含所有策略信息")
    void shouldProvideMeaningfulToString() {
        PortfolioBacktestResult result = runPortfolioBacktest();

        String str = result.toString();
        assertThat(str).contains("MacdCross");
        assertThat(str).contains("RsiCross");
        assertThat(str).contains("60%");
        assertThat(str).contains("40%");
    }

    @Test
    @DisplayName("分配总和不是 100% 时应抛出异常")
    void shouldRejectInvalidAllocation() {
        List<Strategy> strategies = List.of(macdStrategy, rsiStrategy);
        List<BigDecimal> invalidAllocations = List.of(BigDecimal.valueOf(60), BigDecimal.valueOf(30));

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> portfolioEngine.run(config, strategies, invalidAllocations, dataProvider)
        );
    }

    @Test
    @DisplayName("空策略列表应抛出异常")
    void shouldRejectEmptyStrategies() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> portfolioEngine.run(config, List.of(), List.of(), dataProvider)
        );
    }

    /**
     * 运行组合回测的辅助方法。
     */
    private PortfolioBacktestResult runPortfolioBacktest() {
        List<Strategy> strategies = List.of(macdStrategy, rsiStrategy);
        List<BigDecimal> allocations = List.of(MACD_ALLOCATION, RSI_ALLOCATION);
        return portfolioEngine.run(config, strategies, allocations, dataProvider);
    }
}
