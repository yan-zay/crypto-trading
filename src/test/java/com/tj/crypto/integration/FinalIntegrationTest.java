package com.tj.crypto.integration;

import com.tj.crypto.backtest.data.CsvHistoricalDataProvider;
import com.tj.crypto.backtest.engine.BacktestConfig;
import com.tj.crypto.backtest.engine.BacktestEngine;
import com.tj.crypto.backtest.engine.BacktestResult;
import com.tj.crypto.backtest.engine.PortfolioBacktestEngine;
import com.tj.crypto.backtest.engine.PortfolioBacktestResult;
import com.tj.crypto.backtest.report.PerformanceCalculator;
import com.tj.crypto.backtest.report.PerformanceReport;
import com.tj.crypto.central.StrategyEngine;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.event.InMemoryEventBus;
import com.tj.crypto.execution.ExecutionEngine;
import com.tj.crypto.execution.FixedSlippageModel;
import com.tj.crypto.factor.FactorProperties;
import com.tj.crypto.factor.cache.InMemoryBarCache;
import com.tj.crypto.factor.core.FactorCalculator;
import com.tj.crypto.factor.technical.BollingerBandFactor;
import com.tj.crypto.factor.technical.MacdFactor;
import com.tj.crypto.factor.technical.RsiFactor;
import com.tj.crypto.factor.technical.SuperTrendFactor;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.EventMetadata;
import com.tj.crypto.marketdata.model.LiquidationEvent;
import com.tj.crypto.marketdata.model.MarketEvent;
import com.tj.crypto.risk.PositionSizer;
import com.tj.crypto.risk.RiskEngine;
import com.tj.crypto.risk.RiskProperties;
import com.tj.crypto.strategy.config.StrategyProperties;
import com.tj.crypto.strategy.core.InMemorySignalCollector;
import com.tj.crypto.strategy.core.SignalCollector;
import com.tj.crypto.strategy.core.SignalEvent;
import com.tj.crypto.strategy.core.SignalType;
import com.tj.crypto.strategy.core.Strategy;
import com.tj.crypto.strategy.core.StrategyContext;
import com.tj.crypto.strategy.core.StrategyManager;
import com.tj.crypto.strategy.impl.BollingerBreakoutStrategy;
import com.tj.crypto.strategy.impl.LiquidationSpikeStrategyV2;
import com.tj.crypto.strategy.impl.MacdCrossStrategy;
import com.tj.crypto.strategy.impl.RsiCrossStrategy;
import com.tj.crypto.strategy.impl.SuperTrendStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 最终集成测试。
 * 验证所有核心组件协同工作的能力。
 *
 * <p>测试覆盖：
 * <ol>
 *   <li>完整数据管线 — LiquidationEvent → Normalizer → EventBus → StrategyEngine → SignalCollector</li>
 *   <li>完整回测管线 — CSV 数据 → BacktestEngine → ExecutionEngine → PerformanceReport</li>
 *   <li>Admin API 端点可访问性（通过 AdminController 单元测试覆盖）</li>
 *   <li>策略热加载 — 启用/禁用策略后验证事件分发变化</li>
 *   <li>多策略组合 — 4 策略组合回测完成</li>
 * </ol>
 */
class FinalIntegrationTest {

    // ========================================================================
    // Test 1: 完整数据管线
    // LiquidationEvent → EventBus → StrategyEngine → SignalCollector
    // ========================================================================

    @Nested
    @DisplayName("Test 1: 完整数据管线")
    class DataPipelineIntegration {

        private InMemoryEventBus eventBus;
        private StrategyEngine strategyEngine;
        private InMemorySignalCollector signalCollector;
        private ThreadPoolTaskExecutor executor;

        @BeforeEach
        void setUp() {
            eventBus = new InMemoryEventBus();
            signalCollector = new InMemorySignalCollector();

            executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(2);
            executor.setMaxPoolSize(4);
            executor.setQueueCapacity(10);
            executor.setThreadNamePrefix("final-pipeline-");
            executor.initialize();

            StrategyContext context = mock(StrategyContext.class);

            LiquidationSpikeStrategyV2 strategy = new LiquidationSpikeStrategyV2();
            strategy.setThresholdUsd(new BigDecimal("1000000"));

            StrategyProperties properties = new StrategyProperties();
            StrategyManager strategyManager = new StrategyManager(List.of(strategy), properties);

            strategyEngine = new StrategyEngine(executor, eventBus, context, signalCollector, strategyManager);
            strategyEngine.init();
        }

        @Test
        @DisplayName("大额爆仓事件应通过完整管线生成信号")
        void shouldGenerateSignalThroughFullPipeline() throws Exception {
            // Arrange
            Instrument instrument = Instrument.of(Exchange.COINGLASS, MarketType.PERPETUAL, "BTCUSDT");
            EventMetadata metadata = EventMetadata.of(Exchange.COINGLASS, System.currentTimeMillis());
            LiquidationEvent event = new LiquidationEvent(
                    instrument, metadata, OrderSide.LONG,
                    BigDecimal.valueOf(95000), BigDecimal.TEN,
                    BigDecimal.valueOf(2_000_000), "Binance");

            CountDownLatch latch = new CountDownLatch(1);
            eventBus.subscribe(LiquidationEvent.class, e -> latch.countDown());

            // Act
            eventBus.publish(event);

            // Assert
            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(500);

            List<SignalEvent> signals = signalCollector.getSignals("LiquidationSpike");
            assertThat(signals).hasSize(1);
            assertThat(signals.get(0).instrument().symbol()).isEqualTo("BTCUSDT");
            assertThat(signals.get(0).reason()).contains("2000000");
        }

        @Test
        @DisplayName("小额爆仓事件不应生成信号")
        void shouldNotGenerateSignalForSmallLiquidation() throws Exception {
            // Arrange
            Instrument instrument = Instrument.of(Exchange.COINGLASS, MarketType.PERPETUAL, "BTCUSDT");
            EventMetadata metadata = EventMetadata.of(Exchange.COINGLASS, System.currentTimeMillis());
            LiquidationEvent event = new LiquidationEvent(
                    instrument, metadata, OrderSide.SHORT,
                    BigDecimal.valueOf(95000), BigDecimal.TEN,
                    BigDecimal.valueOf(500_000), "Binance");

            CountDownLatch latch = new CountDownLatch(1);
            eventBus.subscribe(LiquidationEvent.class, e -> latch.countDown());

            // Act
            eventBus.publish(event);

            // Assert
            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(500);

            List<SignalEvent> signals = signalCollector.getSignals("LiquidationSpike");
            assertThat(signals).isEmpty();
        }

        @Test
        @DisplayName("BarEvent 应通过父类型传播到达 MarketEvent 订阅者")
        void shouldPropagateBarEventToMarketEventSubscribers() throws Exception {
            // Arrange
            Instrument instrument = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
            EventMetadata metadata = EventMetadata.of(Exchange.BINANCE, System.currentTimeMillis());
            BarEvent bar = new BarEvent(instrument, metadata, Timeframe.M1,
                    BigDecimal.valueOf(95000), BigDecimal.valueOf(95500),
                    BigDecimal.valueOf(94500), BigDecimal.valueOf(95200),
                    BigDecimal.valueOf(100), BigDecimal.valueOf(9520000), true);

            CountDownLatch latch = new CountDownLatch(1);
            eventBus.subscribe(MarketEvent.class, e -> latch.countDown());

            // Act
            eventBus.publish(bar);

            // Assert
            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    // ========================================================================
    // Test 2: 完整回测管线
    // CSV 数据 → BacktestEngine → ExecutionEngine → PerformanceReport
    // ========================================================================

    @Nested
    @DisplayName("Test 2: 完整回测管线")
    class BacktestPipelineIntegration {

        private BacktestEngine engine;
        private Instrument btcUsdt;
        private BacktestConfig config;
        private CsvHistoricalDataProvider dataProvider;

        @BeforeEach
        void setUp() throws URISyntaxException {
            PerformanceCalculator performanceCalculator = new PerformanceCalculator();
            List<FactorCalculator> factorCalculators = List.of();
            RiskProperties riskProperties = new RiskProperties();
            ExecutionEngine executionEngine = new ExecutionEngine(
                    new RiskEngine(List.of()), new PositionSizer(), new FixedSlippageModel(riskProperties));
            engine = new BacktestEngine(performanceCalculator, factorCalculators, executionEngine);

            btcUsdt = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");

            long startTime = 1_700_000_000_000L;
            long endTime = 1_700_005_940_000L;
            config = new BacktestConfig(btcUsdt, Timeframe.M1, startTime, endTime,
                    BigDecimal.valueOf(100_000));

            Path csvPath = Paths.get(Objects.requireNonNull(
                    getClass().getClassLoader().getResource("backtest/btcusdt_1m_sample.csv")).toURI());
            dataProvider = new CsvHistoricalDataProvider(csvPath, Exchange.BINANCE, MarketType.PERPETUAL);
        }

        @Test
        @DisplayName("CSV 数据 → 策略 → 回测引擎 → 性能报告 完整流程")
        void shouldRunFullBacktestFromCsvToReport() {
            // Arrange: 使用简单的买入-持有策略
            Strategy strategy = new Strategy() {
                private boolean bought = false;

                @Override
                public String name() { return "BuyAndHold"; }

                @Override
                public Set<Class<? extends MarketEvent>> listenedEvents() { return Set.of(BarEvent.class); }

                @Override
                public SignalEvent onEvent(MarketEvent event, StrategyContext context) {
                    if (!bought && event instanceof BarEvent bar && bar.closed()) {
                        bought = true;
                        return new SignalEvent(name(), bar.instrument(), SignalType.BUY,
                                BigDecimal.ONE, "Buy on first closed bar",
                                Map.of(), bar.metadata().exchangeTimestamp());
                    }
                    return null;
                }
            };

            // Act
            BacktestResult result = engine.run(config, strategy, dataProvider);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.signals()).isNotEmpty();
            assertThat(result.performanceReport()).isNotNull();
            assertThat(result.performanceReport().initialBalance())
                    .isEqualByComparingTo(BigDecimal.valueOf(100_000));
            assertThat(result.finalBalance()).isGreaterThan(BigDecimal.ZERO);

            // 验证交易价格不为 0
            result.trades().forEach(trade -> {
                assertThat(trade.entryPrice()).isNotEqualByComparingTo(BigDecimal.ZERO);
                assertThat(trade.exitPrice()).isNotEqualByComparingTo(BigDecimal.ZERO);
            });
        }

        @Test
        @DisplayName("空策略回测应保持初始资金不变")
        void shouldPreserveInitialBalanceWithNoSignals() {
            // Arrange
            Strategy neverSignal = new Strategy() {
                @Override
                public String name() { return "NeverSignal"; }
                @Override
                public Set<Class<? extends MarketEvent>> listenedEvents() { return Set.of(BarEvent.class); }
                @Override
                public SignalEvent onEvent(MarketEvent event, StrategyContext context) { return null; }
            };

            // Act
            BacktestResult result = engine.run(config, neverSignal, dataProvider);

            // Assert
            assertThat(result.signals()).isEmpty();
            assertThat(result.trades()).isEmpty();
            assertThat(result.finalBalance()).isEqualByComparingTo(BigDecimal.valueOf(100_000));
            assertThat(result.performanceReport().totalTrades()).isEqualTo(0);
        }
    }

    // ========================================================================
    // Test 3: Admin API 端点可访问性
    // 通过 StrategyManager + AdminService 验证管理功能
    // ========================================================================

    @Nested
    @DisplayName("Test 3: Admin API 功能验证")
    class AdminApiIntegration {

        @Test
        @DisplayName("StrategyManager 应正确管理策略启用/禁用状态")
        void shouldManageStrategyEnabledState() {
            // Arrange
            LiquidationSpikeStrategyV2 strategy = new LiquidationSpikeStrategyV2();
            StrategyProperties properties = new StrategyProperties();
            StrategyManager manager = new StrategyManager(List.of(strategy), properties);

            // Assert: 默认启用
            assertThat(manager.isStrategyEnabled("LiquidationSpike")).isTrue();
            assertThat(manager.getActiveStrategies()).hasSize(1);

            // Act: 禁用
            boolean disabled = manager.disableStrategy("LiquidationSpike");

            // Assert: 禁用后状态变化
            assertThat(disabled).isTrue();
            assertThat(manager.isStrategyEnabled("LiquidationSpike")).isFalse();
            assertThat(manager.getActiveStrategies()).isEmpty();

            // Act: 重新启用
            boolean enabled = manager.enableStrategy("LiquidationSpike");

            // Assert: 启用后恢复
            assertThat(enabled).isTrue();
            assertThat(manager.isStrategyEnabled("LiquidationSpike")).isTrue();
            assertThat(manager.getActiveStrategies()).hasSize(1);
        }

        @Test
        @DisplayName("StrategyManager 应正确查询策略信息")
        void shouldQueryStrategyInfo() {
            // Arrange
            LiquidationSpikeStrategyV2 liqStrategy = new LiquidationSpikeStrategyV2();
            StrategyProperties properties = new StrategyProperties();
            StrategyManager manager = new StrategyManager(List.of(liqStrategy), properties);

            // Assert
            assertThat(manager.getStrategy("LiquidationSpike")).isPresent();
            assertThat(manager.getStrategy("Unknown")).isEmpty();
            assertThat(manager.getAllStrategies()).hasSize(1);
            assertThat(manager.getStrategy("LiquidationSpike").get().listenedEvents())
                    .contains(LiquidationEvent.class);
        }
    }

    // ========================================================================
    // Test 4: 策略热加载
    // 启用/禁用策略后验证事件分发变化
    // ========================================================================

    @Nested
    @DisplayName("Test 4: 策略热加载")
    class StrategyHotReloadIntegration {

        private InMemoryEventBus eventBus;
        private InMemorySignalCollector signalCollector;
        private ThreadPoolTaskExecutor executor;
        private StrategyManager strategyManager;
        private StrategyEngine strategyEngine;

        @BeforeEach
        void setUp() {
            eventBus = new InMemoryEventBus();
            signalCollector = new InMemorySignalCollector();

            executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(2);
            executor.setMaxPoolSize(4);
            executor.setQueueCapacity(10);
            executor.setThreadNamePrefix("hot-reload-");
            executor.initialize();

            StrategyContext context = mock(StrategyContext.class);

            LiquidationSpikeStrategyV2 strategy = new LiquidationSpikeStrategyV2();
            strategy.setThresholdUsd(new BigDecimal("1000000"));

            StrategyProperties properties = new StrategyProperties();
            strategyManager = new StrategyManager(List.of(strategy), properties);

            strategyEngine = new StrategyEngine(executor, eventBus, context, signalCollector, strategyManager);
            strategyEngine.init();
        }

        @Test
        @DisplayName("禁用策略后不应生成信号")
        void shouldNotGenerateSignalsWhenStrategyDisabled() throws Exception {
            // Arrange: 禁用策略
            strategyManager.disableStrategy("LiquidationSpike");

            Instrument instrument = Instrument.of(Exchange.COINGLASS, MarketType.PERPETUAL, "BTCUSDT");
            EventMetadata metadata = EventMetadata.of(Exchange.COINGLASS, System.currentTimeMillis());
            LiquidationEvent event = new LiquidationEvent(
                    instrument, metadata, OrderSide.LONG,
                    BigDecimal.valueOf(95000), BigDecimal.TEN,
                    BigDecimal.valueOf(2_000_000), "Binance");

            CountDownLatch latch = new CountDownLatch(1);
            eventBus.subscribe(LiquidationEvent.class, e -> latch.countDown());

            // Act
            eventBus.publish(event);

            // Assert
            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(500);

            assertThat(signalCollector.getSignals("LiquidationSpike")).isEmpty();
        }

        @Test
        @DisplayName("重新启用策略后应恢复信号生成")
        void shouldResumeSignalGenerationAfterReEnabling() throws Exception {
            // Arrange: 先禁用再启用
            strategyManager.disableStrategy("LiquidationSpike");

            Instrument instrument = Instrument.of(Exchange.COINGLASS, MarketType.PERPETUAL, "BTCUSDT");
            EventMetadata metadata = EventMetadata.of(Exchange.COINGLASS, System.currentTimeMillis());

            // 发布一个事件（策略禁用中）
            LiquidationEvent event1 = new LiquidationEvent(
                    instrument, metadata, OrderSide.LONG,
                    BigDecimal.valueOf(95000), BigDecimal.TEN,
                    BigDecimal.valueOf(2_000_000), "Binance");

            CountDownLatch latch1 = new CountDownLatch(1);
            eventBus.subscribe(LiquidationEvent.class, e -> latch1.countDown());
            eventBus.publish(event1);
            assertThat(latch1.await(3, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(300);

            // Assert: 禁用期间无信号
            assertThat(signalCollector.getSignals("LiquidationSpike")).isEmpty();

            // Act: 重新启用
            strategyManager.enableStrategy("LiquidationSpike");

            // 发布第二个事件
            LiquidationEvent event2 = new LiquidationEvent(
                    instrument, EventMetadata.of(Exchange.COINGLASS, System.currentTimeMillis()),
                    OrderSide.SHORT,
                    BigDecimal.valueOf(95000), BigDecimal.TEN,
                    BigDecimal.valueOf(3_000_000), "Binance");

            CountDownLatch latch2 = new CountDownLatch(1);
            eventBus.subscribe(LiquidationEvent.class, e -> latch2.countDown());
            eventBus.publish(event2);

            // Assert: 启用后应生成信号
            assertThat(latch2.await(3, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(500);

            assertThat(signalCollector.getSignals("LiquidationSpike")).hasSize(1);
        }
    }

    // ========================================================================
    // Test 5: 多策略组合回测
    // 4 策略组合回测完成
    // ========================================================================

    @Nested
    @DisplayName("Test 5: 多策略组合回测")
    class MultiStrategyPortfolioIntegration {

        private PortfolioBacktestEngine portfolioEngine;
        private Instrument btcUsdt;
        private BacktestConfig config;
        private CsvHistoricalDataProvider dataProvider;

        @BeforeEach
        void setUp() throws URISyntaxException {
            InMemoryEventBus sharedEventBus = new InMemoryEventBus();
            InMemoryBarCache sharedBarCache = new InMemoryBarCache(sharedEventBus);
            FactorProperties factorProperties = new FactorProperties();

            MacdFactor macdFactor = new MacdFactor(sharedBarCache, factorProperties);
            RsiFactor rsiFactor = new RsiFactor(sharedBarCache, factorProperties);
            BollingerBandFactor bbFactor = new BollingerBandFactor(sharedBarCache, factorProperties);
            SuperTrendFactor superTrendFactor = new SuperTrendFactor(sharedBarCache);
            List<FactorCalculator> factorCalculators = List.of(macdFactor, rsiFactor, bbFactor, superTrendFactor);

            PerformanceCalculator performanceCalculator = new PerformanceCalculator();
            RiskProperties riskProperties = new RiskProperties();
            ExecutionEngine executionEngine = new ExecutionEngine(
                    new RiskEngine(List.of()), new PositionSizer(), new FixedSlippageModel(riskProperties));

            BacktestEngine backtestEngine = new BacktestEngine(performanceCalculator, factorCalculators, executionEngine);
            portfolioEngine = new PortfolioBacktestEngine(backtestEngine, performanceCalculator);

            btcUsdt = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
            long startTime = 1_700_000_000_000L;
            long endTime = 1_700_005_940_000L;
            config = new BacktestConfig(btcUsdt, Timeframe.M1, startTime, endTime,
                    BigDecimal.valueOf(100_000));

            Path csvPath = Paths.get(Objects.requireNonNull(
                    getClass().getClassLoader().getResource("backtest/btcusdt_1m_sample.csv")).toURI());
            dataProvider = new CsvHistoricalDataProvider(csvPath, Exchange.BINANCE, MarketType.PERPETUAL);
        }

        @Test
        @DisplayName("4 策略组合回测应完成并返回有效结果")
        void shouldCompleteFourStrategyPortfolioBacktest() {
            // Arrange
            List<Strategy> strategies = List.of(
                    new MacdCrossStrategy(),
                    new RsiCrossStrategy(),
                    new BollingerBreakoutStrategy(),
                    new SuperTrendStrategy());
            List<BigDecimal> allocations = List.of(
                    BigDecimal.valueOf(25), BigDecimal.valueOf(25),
                    BigDecimal.valueOf(25), BigDecimal.valueOf(25));

            // Act
            PortfolioBacktestResult result = portfolioEngine.run(config, strategies, allocations, dataProvider);

            // Assert: 基本完整性
            assertThat(result).isNotNull();
            assertThat(result.getStrategyCount()).isEqualTo(4);
            assertThat(result.combinedReport()).isNotNull();
            assertThat(result.combinedTrades()).isNotNull();

            // Assert: 每个策略都有独立结果
            assertThat(result.getStrategyResult("MacdCross")).isNotNull();
            assertThat(result.getStrategyResult("RsiCross")).isNotNull();
            assertThat(result.getStrategyResult("BollingerBreakout")).isNotNull();
            assertThat(result.getStrategyResult("SuperTrend")).isNotNull();

            // Assert: 资金分配正确
            Map<String, BigDecimal> allocationMap = result.allocationMap();
            BigDecimal totalAllocation = allocationMap.values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(totalAllocation).isEqualByComparingTo(BigDecimal.valueOf(100));

            // Assert: 合并最终余额等于各策略余额之和
            BigDecimal sumOfBalances = BigDecimal.ZERO;
            for (String name : List.of("MacdCross", "RsiCross", "BollingerBreakout", "SuperTrend")) {
                sumOfBalances = sumOfBalances.add(result.getStrategyResult(name).finalBalance());
            }
            assertThat(result.combinedReport().finalBalance()).isEqualByComparingTo(sumOfBalances);

            // Assert: 合并交易数等于各策略交易数之和
            int totalPerStrategy = 0;
            for (String name : List.of("MacdCross", "RsiCross", "BollingerBreakout", "SuperTrend")) {
                totalPerStrategy += result.getStrategyResult(name).trades().size();
            }
            assertThat(result.combinedTrades().size()).isEqualTo(totalPerStrategy);

            // Assert: 合并交易按时间排序
            List<com.tj.crypto.backtest.portfolio.Trade> trades = result.combinedTrades();
            for (int i = 1; i < trades.size(); i++) {
                assertThat(trades.get(i).entryTime())
                        .isGreaterThanOrEqualTo(trades.get(i - 1).entryTime());
            }

            // Assert: 性能报告字段合理
            PerformanceReport report = result.combinedReport();
            assertThat(report.initialBalance()).isEqualByComparingTo(BigDecimal.valueOf(100_000));
            assertThat(report.startTime()).isEqualTo(config.startTime());
            assertThat(report.endTime()).isEqualTo(config.endTime());
            assertThat(report.totalTrades()).isEqualTo(result.combinedTrades().size());
            assertThat(report.totalTrades()).isEqualTo(report.winningTrades() + report.losingTrades());
        }

        @Test
        @DisplayName("组合回测 toString 应包含所有策略信息")
        void shouldProvideMeaningfulToString() {
            // Arrange
            List<Strategy> strategies = List.of(
                    new MacdCrossStrategy(),
                    new RsiCrossStrategy(),
                    new BollingerBreakoutStrategy(),
                    new SuperTrendStrategy());
            List<BigDecimal> allocations = List.of(
                    BigDecimal.valueOf(25), BigDecimal.valueOf(25),
                    BigDecimal.valueOf(25), BigDecimal.valueOf(25));

            // Act
            PortfolioBacktestResult result = portfolioEngine.run(config, strategies, allocations, dataProvider);

            // Assert
            String str = result.toString();
            assertThat(str).contains("MacdCross");
            assertThat(str).contains("RsiCross");
            assertThat(str).contains("BollingerBreakout");
            assertThat(str).contains("SuperTrend");
        }
    }
}
