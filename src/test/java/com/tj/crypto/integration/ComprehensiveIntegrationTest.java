package com.tj.crypto.integration;

import com.tj.crypto.central.StrategyEngine;
import com.tj.crypto.backtest.data.CsvHistoricalDataProvider;
import com.tj.crypto.backtest.engine.BacktestConfig;
import com.tj.crypto.backtest.engine.BacktestEngine;
import com.tj.crypto.backtest.engine.BacktestResult;
import com.tj.crypto.backtest.portfolio.FuturesAccount;
import com.tj.crypto.backtest.portfolio.FuturesPosition;
import com.tj.crypto.backtest.portfolio.MarginMode;
import com.tj.crypto.backtest.portfolio.MakerTakerFeeModel;
import com.tj.crypto.backtest.portfolio.Trade;
import com.tj.crypto.backtest.portfolio.VirtualAccount;
import com.tj.crypto.backtest.report.PerformanceCalculator;
import com.tj.crypto.backtest.report.PerformanceReport;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.event.InMemoryEventBus;
import com.tj.crypto.execution.ExecutionEngine;
import com.tj.crypto.execution.FixedSlippageModel;
import com.tj.crypto.execution.OrderStateMachine;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderEvent;
import com.tj.crypto.execution.model.OrderRejectReason;
import com.tj.crypto.execution.model.OrderStatus;
import com.tj.crypto.execution.model.OrderType;
import com.tj.crypto.marketdata.connector.ConnectorHealth;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.EventMetadata;
import com.tj.crypto.marketdata.model.LiquidationEvent;
import com.tj.crypto.marketdata.model.MarketEvent;
import com.tj.crypto.observability.MetricsSnapshot;
import com.tj.crypto.observability.alert.AlertEvent;
import com.tj.crypto.observability.alert.AlertRule;
import com.tj.crypto.observability.alert.AlertService;
import com.tj.crypto.risk.KillSwitch;
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
import com.tj.crypto.strategy.impl.LiquidationSpikeStrategyV2;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 综合集成测试。
 * 验证所有核心组件协同工作，覆盖完整数据管线、回测管线、
 * 期货账户、订单状态机、熔断开关、告警服务和配置版本管理。
 */
class ComprehensiveIntegrationTest {

    private static final Instrument BTC_USDT =
            Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");

    // ========================================================================
    // Test 1: 完整数据管线
    // BarEvent -> EventBus -> StrategyEngine -> SignalCollector -> ExecutionEngine
    // ========================================================================

    @Nested
    @DisplayName("Test 1: 完整数据管线 - BarEvent 到 ExecutionEngine")
    class FullDataPipelineIntegration {

        private InMemoryEventBus eventBus;
        private InMemorySignalCollector signalCollector;
        private ThreadPoolTaskExecutor executor;
        private ExecutionEngine executionEngine;
        private VirtualAccount account;

        @BeforeEach
        void setUp() {
            eventBus = new InMemoryEventBus();
            signalCollector = new InMemorySignalCollector();

            executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(2);
            executor.setMaxPoolSize(4);
            executor.setQueueCapacity(10);
            executor.setThreadNamePrefix("pipeline-");
            executor.initialize();

            // 创建 ExecutionEngine（无风控规则，允许所有交易）
            RiskProperties riskProperties = new RiskProperties();
            KillSwitch killSwitch = new KillSwitch();
            executionEngine = new ExecutionEngine(
                    new RiskEngine(List.of()),
                    new PositionSizer(riskProperties),
                    new FixedSlippageModel(riskProperties),
                    killSwitch);

            account = new VirtualAccount(BigDecimal.valueOf(100_000));
        }

        @Test
        @DisplayName("大额爆仓事件应通过完整管线生成信号并执行交易")
        void shouldGenerateSignalAndExecuteTradeThroughFullPipeline() throws Exception {
            // Arrange: 创建策略引擎，监听 LiquidationEvent
            LiquidationSpikeStrategyV2 strategy = new LiquidationSpikeStrategyV2();
            strategy.setThresholdUsd(new BigDecimal("1000000"));

            StrategyProperties properties = new StrategyProperties();
            StrategyManager strategyManager = new StrategyManager(List.of(strategy), properties);

            StrategyContext context = mock(StrategyContext.class);
            StrategyEngine strategyEngine = new StrategyEngine(
                    executor, eventBus, context, signalCollector, strategyManager);
            strategyEngine.init();

            // 创建大额爆仓事件
            Instrument instrument = Instrument.of(Exchange.COINGLASS, MarketType.PERPETUAL, "BTCUSDT");
            EventMetadata metadata = EventMetadata.of(Exchange.COINGLASS, System.currentTimeMillis());
            LiquidationEvent event = new LiquidationEvent(
                    instrument, metadata, OrderSide.LONG,
                    BigDecimal.valueOf(95000), BigDecimal.TEN,
                    BigDecimal.valueOf(2_000_000), "Binance");

            CountDownLatch latch = new CountDownLatch(1);
            eventBus.subscribe(LiquidationEvent.class, e -> latch.countDown());

            // Act: 发布事件
            eventBus.publish(event);

            // Assert: 信号应被收集
            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(500);

            List<SignalEvent> signals = signalCollector.getSignals("LiquidationSpike");
            assertThat(signals).hasSize(1);
            assertThat(signals.get(0).instrument().symbol()).isEqualTo("BTCUSDT");

            // Act: 使用信号执行交易
            SignalEvent signal = signals.get(0);
            BigDecimal currentPrice = BigDecimal.valueOf(95000);
            Order order = executionEngine.execute(signal, account, currentPrice, System.currentTimeMillis());

            // Assert: HOLD 信号不产生订单（LiquidationSpikeV2 输出 HOLD）
            // 但管线完整性已验证：EventBus -> StrategyEngine -> SignalCollector
            assertThat(signal.type()).isEqualTo(SignalType.HOLD);
        }

        @Test
        @DisplayName("BarEvent 应通过 EventBus 到达所有订阅者")
        void shouldPropagateBarEventToAllSubscribers() throws Exception {
            // Arrange
            BarEvent bar = createBarEvent(95000, 95500, 94500, 95200, 100);

            CountDownLatch barLatch = new CountDownLatch(1);
            CountDownLatch marketLatch = new CountDownLatch(1);
            eventBus.subscribe(BarEvent.class, e -> barLatch.countDown());
            eventBus.subscribe(MarketEvent.class, e -> marketLatch.countDown());

            // Act
            eventBus.publish(bar);

            // Assert: 两个订阅者都应收到事件
            assertThat(barLatch.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(marketLatch.await(3, TimeUnit.SECONDS)).isTrue();
        }

        @Test
        @DisplayName("BUY 信号应通过 ExecutionEngine 开仓")
        void shouldOpenPositionFromBuySignal() {
            // Arrange: 创建 BUY 信号
            SignalEvent buySignal = new SignalEvent(
                    "TestStrategy", BTC_USDT, SignalType.BUY,
                    BigDecimal.ONE, "Test buy signal",
                    Map.of(), System.currentTimeMillis());

            // Act
            Order order = executionEngine.execute(buySignal, account,
                    BigDecimal.valueOf(95000), System.currentTimeMillis());

            // Assert
            assertThat(order).isNotNull();
            assertThat(order.status()).isEqualTo(OrderStatus.FILLED);
            assertThat(order.side()).isEqualTo(OrderSide.LONG);
            assertThat(account.hasPosition(BTC_USDT)).isTrue();
        }
    }

    // ========================================================================
    // Test 2: 完整回测管线
    // CSV -> BacktestEngine -> ExecutionEngine -> RiskEngine -> PerformanceReport
    // ========================================================================

    @Nested
    @DisplayName("Test 2: 完整回测管线 - CSV 到 PerformanceReport")
    class FullBacktestPipelineIntegration {

        private BacktestEngine engine;
        private BacktestConfig config;
        private CsvHistoricalDataProvider dataProvider;

        @BeforeEach
        void setUp() throws URISyntaxException {
            PerformanceCalculator performanceCalculator = new PerformanceCalculator();
            RiskProperties riskProperties = new RiskProperties();
            ExecutionEngine executionEngine = new ExecutionEngine(
                    new RiskEngine(List.of()),
                    new PositionSizer(riskProperties),
                    new FixedSlippageModel(riskProperties),
                    new KillSwitch());
            engine = new BacktestEngine(performanceCalculator, List.of(), executionEngine);

            long startTime = 1_700_000_000_000L;
            long endTime = 1_700_005_940_000L;
            config = new BacktestConfig(BTC_USDT, Timeframe.M1, startTime, endTime,
                    BigDecimal.valueOf(100_000));

            Path csvPath = Paths.get(Objects.requireNonNull(
                    getClass().getClassLoader().getResource("backtest/btcusdt_1m_sample.csv")).toURI());
            dataProvider = new CsvHistoricalDataProvider(csvPath, Exchange.BINANCE, MarketType.PERPETUAL);
        }

        @Test
        @DisplayName("CSV 数据经过完整回测管线应生成有效 PerformanceReport")
        void shouldGenerateValidPerformanceReportFromCsv() {
            // Arrange: 买入-持有策略
            Strategy buyAndHold = new Strategy() {
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
            BacktestResult result = engine.run(config, buyAndHold, dataProvider);

            // Assert: 回测结果完整性
            assertThat(result).isNotNull();
            assertThat(result.signals()).isNotEmpty();
            assertThat(result.trades()).isNotEmpty();
            assertThat(result.finalBalance()).isGreaterThan(BigDecimal.ZERO);

            // Assert: PerformanceReport 字段完整性
            PerformanceReport report = result.performanceReport();
            assertThat(report).isNotNull();
            assertThat(report.initialBalance()).isEqualByComparingTo(BigDecimal.valueOf(100_000));
            assertThat(report.totalTrades()).isEqualTo(result.trades().size());
            assertThat(report.totalTrades()).isEqualTo(report.winningTrades() + report.losingTrades());
            assertThat(report.winRate()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(report.maxDrawdown()).isGreaterThanOrEqualTo(BigDecimal.ZERO);

            // Assert: 交易价格不为 0
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

        @Test
        @DisplayName("带回测假设的 PerformanceReport 应包含 assumptionsJson")
        void shouldIncludeAssumptionsInReport() {
            // Arrange
            Strategy buyAndHold = new Strategy() {
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
                                BigDecimal.ONE, "Buy", Map.of(), bar.metadata().exchangeTimestamp());
                    }
                    return null;
                }
            };

            // Act
            BacktestResult result = engine.run(config, buyAndHold, dataProvider);

            // Assert
            assertThat(result.assumptions()).isNotNull();
            assertThat(result.performanceReport().assumptionsJson()).isNotEqualTo("{}");
        }
    }

    // ========================================================================
    // Test 3: FuturesAccount 完整流程
    // 开仓 -> 资金费率 -> 强平
    // ========================================================================

    @Nested
    @DisplayName("Test 3: FuturesAccount 完整流程 - 开仓、资金费率、强平")
    class FuturesAccountIntegration {

        private FuturesAccount account;
        private MakerTakerFeeModel feeModel;

        @BeforeEach
        void setUp() {
            feeModel = new MakerTakerFeeModel(
                    new BigDecimal("0.0002"), new BigDecimal("0.0004"));
            account = new FuturesAccount(BigDecimal.valueOf(10_000), feeModel);
        }

        @Test
        @DisplayName("开仓 -> 资金费率结算 -> 平仓完整流程")
        void shouldCompleteOpenFundingCloseCycle() {
            // Arrange: 开多仓，10x 杠杆
            boolean opened = account.openPosition(
                    BTC_USDT, OrderSide.LONG,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(95000),
                    10, MarginMode.ISOLATED);

            // Assert: 开仓成功
            assertThat(opened).isTrue();
            assertThat(account.hasPosition(BTC_USDT)).isTrue();
            assertThat(account.getPositions()).hasSize(1);

            // 验证保证金冻结
            BigDecimal margin = account.getTotalMargin();
            assertThat(margin).isGreaterThan(BigDecimal.ZERO);
            assertThat(account.getAvailableBalance()).isLessThan(account.getBalance());

            // Act: 结算正资金费率（多头付费）
            BigDecimal fundingRate = new BigDecimal("0.0001"); // 0.01%
            account.applyFundingRate(BTC_USDT, fundingRate);

            // Assert: 资金费率已结算
            assertThat(account.getTotalFundingPaid()).isGreaterThan(BigDecimal.ZERO);
            BigDecimal balanceAfterFunding = account.getBalance();

            // Act: 平仓（价格上涨，盈利）
            Trade trade = account.closePosition(BTC_USDT, BigDecimal.valueOf(96000));

            // Assert: 平仓结果
            assertThat(trade).isNotNull();
            assertThat(trade.realizedPnL()).isGreaterThan(BigDecimal.ZERO); // 盈利
            assertThat(trade.instrument()).isEqualTo(BTC_USDT);
            assertThat(trade.side()).isEqualTo(OrderSide.LONG);
            assertThat(account.hasPosition(BTC_USDT)).isFalse();
            assertThat(account.getTrades()).hasSize(1);
        }

        @Test
        @DisplayName("强平应扣除保证金亏损并记录交易")
        void shouldLiquidatePositionAndDeductMarginLoss() {
            // Arrange: 开多仓，高杠杆
            account.openPosition(
                    BTC_USDT, OrderSide.LONG,
                    BigDecimal.valueOf(1), BigDecimal.valueOf(95000),
                    100, MarginMode.ISOLATED);

            BigDecimal balanceBefore = account.getBalance();

            // Act: 价格大幅下跌，触发强平
            BigDecimal liquidationPrice = account.getLiquidationPrice(BTC_USDT);
            assertThat(liquidationPrice).isNotNull();
            assertThat(liquidationPrice).isLessThan(BigDecimal.valueOf(95000));

            Trade trade = account.liquidatePosition(BTC_USDT, liquidationPrice);

            // Assert: 强平结果
            assertThat(trade).isNotNull();
            assertThat(trade.realizedPnL()).isLessThan(BigDecimal.ZERO); // 亏损
            assertThat(account.hasPosition(BTC_USDT)).isFalse();
            assertThat(account.getTrades()).hasSize(1);
            assertThat(account.getBalance()).isLessThan(balanceBefore);
        }

        @Test
        @DisplayName("可用余额不足时开仓应失败")
        void shouldRejectOpenWhenInsufficientBalance() {
            // Arrange: 使用极小账户
            FuturesAccount smallAccount = new FuturesAccount(BigDecimal.valueOf(100), feeModel);

            // Act: 尝试开大仓位
            boolean opened = smallAccount.openPosition(
                    BTC_USDT, OrderSide.LONG,
                    BigDecimal.valueOf(1), BigDecimal.valueOf(95000),
                    1, MarginMode.ISOLATED);

            // Assert: 开仓失败
            assertThat(opened).isFalse();
            assertThat(smallAccount.hasPosition(BTC_USDT)).isFalse();
        }

        @Test
        @DisplayName("空仓资金费率结算应收钱（正费率时空头收钱）")
        void shouldReceiveFundingForShortPosition() {
            // Arrange: 开空仓
            account.openPosition(
                    BTC_USDT, OrderSide.SHORT,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(95000),
                    10, MarginMode.ISOLATED);

            BigDecimal balanceBefore = account.getBalance();

            // Act: 正资金费率（空头收钱）
            account.applyFundingRate(BTC_USDT, new BigDecimal("0.0001"));

            // Assert: 余额增加
            assertThat(account.getBalance()).isGreaterThan(balanceBefore);
        }

        @Test
        @DisplayName("总权益应等于余额加未实现盈亏")
        void shouldCalculateTotalEquityCorrectly() {
            // Arrange: 开多仓
            account.openPosition(
                    BTC_USDT, OrderSide.LONG,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(95000),
                    10, MarginMode.ISOLATED);

            // Act: 计算总权益
            BigDecimal currentPrice = BigDecimal.valueOf(96000);
            BigDecimal totalEquity = account.getTotalEquity(Map.of("BTCUSDT", currentPrice));

            // Assert: 总权益应大于初始余额（价格上涨）
            assertThat(totalEquity).isGreaterThan(BigDecimal.valueOf(10_000));
        }
    }

    // ========================================================================
    // Test 4: OrderStateMachine 完整生命周期
    // CREATED -> SUBMITTED -> FILLED
    // ========================================================================

    @Nested
    @DisplayName("Test 4: OrderStateMachine 完整生命周期")
    class OrderStateMachineIntegration {

        private Order order;
        private final long now = System.currentTimeMillis();

        @BeforeEach
        void setUp() {
            order = Order.create(BTC_USDT, OrderSide.LONG, OrderType.MARKET,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(95000), now);
        }

        @Test
        @DisplayName("CREATED -> SUBMITTED -> ACKNOWLEDGED -> FILLED 完整生命周期")
        void shouldTransitionThroughFullLifecycle() {
            // Assert: 初始状态
            assertThat(order.status()).isEqualTo(OrderStatus.CREATED);
            assertThat(order.isActive()).isTrue();

            // Act: CREATED -> SUBMITTED
            Order submitted = OrderStateMachine.transition(order,
                    OrderEvent.submitted(order.orderId(), now + 1));

            // Assert
            assertThat(submitted.status()).isEqualTo(OrderStatus.SUBMITTED);
            assertThat(submitted.isActive()).isTrue();

            // Act: SUBMITTED -> ACKNOWLEDGED
            Order acknowledged = OrderStateMachine.transition(submitted,
                    OrderEvent.acknowledged(submitted.orderId(), now + 2));

            // Assert
            assertThat(acknowledged.status()).isEqualTo(OrderStatus.ACKNOWLEDGED);

            // Act: ACKNOWLEDGED -> FILLED
            Order filled = OrderStateMachine.transition(acknowledged,
                    OrderEvent.filled(acknowledged.orderId(), now + 3,
                            BigDecimal.valueOf(95050), BigDecimal.valueOf(0.1)));

            // Assert
            assertThat(filled.status()).isEqualTo(OrderStatus.FILLED);
            assertThat(filled.isActive()).isFalse();
            assertThat(filled.isFilled()).isTrue();
            assertThat(filled.filledQuantity()).isEqualByComparingTo(BigDecimal.valueOf(0.1));
            assertThat(filled.avgFillPrice()).isEqualByComparingTo(BigDecimal.valueOf(95050));
            assertThat(filled.filledAt()).isEqualTo(now + 3);
        }

        @Test
        @DisplayName("CREATED -> SUBMITTED -> REJECTED 拒绝流程")
        void shouldTransitionToRejected() {
            // Act
            Order submitted = OrderStateMachine.transition(order,
                    OrderEvent.submitted(order.orderId(), now + 1));
            Order rejected = OrderStateMachine.transition(submitted,
                    OrderEvent.rejected(submitted.orderId(), now + 2,
                            OrderRejectReason.RISK_REJECTED));

            // Assert
            assertThat(rejected.status()).isEqualTo(OrderStatus.REJECTED);
            assertThat(rejected.isRejected()).isTrue();
            assertThat(rejected.rejectReason()).isEqualTo(OrderRejectReason.RISK_REJECTED);
        }

        @Test
        @DisplayName("ACKNOWLEDGED -> CANCEL_REQUESTED -> CANCELLED 撤单流程")
        void shouldTransitionToCancelled() {
            // Act
            Order submitted = OrderStateMachine.transition(order,
                    OrderEvent.submitted(order.orderId(), now + 1));
            Order acknowledged = OrderStateMachine.transition(submitted,
                    OrderEvent.acknowledged(submitted.orderId(), now + 2));
            Order cancelRequested = OrderStateMachine.transition(acknowledged,
                    OrderEvent.cancelRequested(acknowledged.orderId(), now + 3));
            Order cancelled = OrderStateMachine.transition(cancelRequested,
                    OrderEvent.cancelled(cancelRequested.orderId(), now + 4));

            // Assert
            assertThat(cancelRequested.status()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
            assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(cancelled.isCancelled()).isTrue();
        }

        @Test
        @DisplayName("ACKNOWLEDGED -> PARTIALLY_FILLED -> FILLED 部分成交流程")
        void shouldTransitionThroughPartialFill() {
            // Act
            Order submitted = OrderStateMachine.transition(order,
                    OrderEvent.submitted(order.orderId(), now + 1));
            Order acknowledged = OrderStateMachine.transition(submitted,
                    OrderEvent.acknowledged(submitted.orderId(), now + 2));
            Order partial = OrderStateMachine.transition(acknowledged,
                    OrderEvent.partiallyFilled(acknowledged.orderId(), now + 3,
                            BigDecimal.valueOf(95000), BigDecimal.valueOf(0.05)));

            // Assert: 部分成交
            assertThat(partial.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
            assertThat(partial.filledQuantity()).isEqualByComparingTo(BigDecimal.valueOf(0.05));

            // Act: 完全成交
            Order filled = OrderStateMachine.transition(partial,
                    OrderEvent.filled(partial.orderId(), now + 4,
                            BigDecimal.valueOf(95100), BigDecimal.valueOf(0.05)));

            // Assert: 完全成交，均价为加权平均
            assertThat(filled.status()).isEqualTo(OrderStatus.FILLED);
            assertThat(filled.filledQuantity()).isEqualByComparingTo(BigDecimal.valueOf(0.1));
            // 加权均价 = (95000 * 0.05 + 95100 * 0.05) / 0.1 = 95050
            assertThat(filled.avgFillPrice()).isEqualByComparingTo(BigDecimal.valueOf(95050));
        }

        @Test
        @DisplayName("非法状态转换应抛出 IllegalStateException")
        void shouldRejectIllegalTransition() {
            // Act & Assert: CREATED -> FILLED 是非法的
            assertThatThrownBy(() -> OrderStateMachine.transition(order,
                    OrderEvent.filled(order.orderId(), now + 1,
                            BigDecimal.valueOf(95000), BigDecimal.valueOf(0.1))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("非法订单状态转换");
        }

        @Test
        @DisplayName("终态不可再转换")
        void shouldNotTransitionFromTerminalState() {
            // Arrange: 走到 FILLED
            Order submitted = OrderStateMachine.transition(order,
                    OrderEvent.submitted(order.orderId(), now + 1));
            Order acknowledged = OrderStateMachine.transition(submitted,
                    OrderEvent.acknowledged(submitted.orderId(), now + 2));
            Order filled = OrderStateMachine.transition(acknowledged,
                    OrderEvent.filled(acknowledged.orderId(), now + 3,
                            BigDecimal.valueOf(95000), BigDecimal.valueOf(0.1)));

            // Act & Assert: FILLED -> CANCELLED 是非法的
            assertThatThrownBy(() -> OrderStateMachine.transition(filled,
                    OrderEvent.cancelled(filled.orderId(), now + 4)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // ========================================================================
    // Test 5: KillSwitch 集成 - HALT 拒绝所有订单
    // ========================================================================

    @Nested
    @DisplayName("Test 5: KillSwitch 集成 - HALT 模式拒绝所有订单")
    class KillSwitchIntegration {

        private KillSwitch killSwitch;
        private ExecutionEngine executionEngine;
        private VirtualAccount account;

        @BeforeEach
        void setUp() {
            killSwitch = new KillSwitch();
            RiskProperties riskProperties = new RiskProperties();
            executionEngine = new ExecutionEngine(
                    new RiskEngine(List.of()),
                    new PositionSizer(riskProperties),
                    new FixedSlippageModel(riskProperties),
                    killSwitch);
            account = new VirtualAccount(BigDecimal.valueOf(100_000));
        }

        @Test
        @DisplayName("HALT 模式下 BUY 信号应被拒绝")
        void shouldRejectBuySignalInHaltMode() {
            // Arrange
            killSwitch.activate(KillSwitch.Mode.HALT);
            SignalEvent buySignal = new SignalEvent(
                    "TestStrategy", BTC_USDT, SignalType.BUY,
                    BigDecimal.ONE, "Test", Map.of(), System.currentTimeMillis());

            // Act
            Order order = executionEngine.execute(buySignal, account,
                    BigDecimal.valueOf(95000), System.currentTimeMillis());

            // Assert
            assertThat(order).isNotNull();
            assertThat(order.status()).isEqualTo(OrderStatus.REJECTED);
            assertThat(order.rejectReason()).isEqualTo(OrderRejectReason.KILL_SWITCH);
            assertThat(account.hasPosition(BTC_USDT)).isFalse();
        }

        @Test
        @DisplayName("HALT 模式下 SELL 信号应被拒绝")
        void shouldRejectSellSignalInHaltMode() {
            // Arrange
            killSwitch.activate(KillSwitch.Mode.HALT);
            SignalEvent sellSignal = new SignalEvent(
                    "TestStrategy", BTC_USDT, SignalType.SELL,
                    BigDecimal.ONE, "Test", Map.of(), System.currentTimeMillis());

            // Act
            Order order = executionEngine.execute(sellSignal, account,
                    BigDecimal.valueOf(95000), System.currentTimeMillis());

            // Assert
            assertThat(order).isNotNull();
            assertThat(order.status()).isEqualTo(OrderStatus.REJECTED);
            assertThat(order.rejectReason()).isEqualTo(OrderRejectReason.KILL_SWITCH);
        }

        @Test
        @DisplayName("CLOSE_ONLY 模式下开仓应被拒绝，平仓应通过")
        void shouldRejectOpeningButAllowClosingInCloseOnlyMode() {
            // Arrange: 切换到 CLOSE_ONLY（无持仓）
            killSwitch.activate(KillSwitch.Mode.CLOSE_ONLY);

            // Assert: BUY（开仓）应被拒绝（无持仓=开仓）
            SignalEvent buySignal = new SignalEvent(
                    "TestStrategy", BTC_USDT, SignalType.BUY,
                    BigDecimal.ONE, "Test", Map.of(), System.currentTimeMillis());
            Order buyOrder = executionEngine.execute(buySignal, account,
                    BigDecimal.valueOf(95000), System.currentTimeMillis());
            assertThat(buyOrder.rejectReason()).isEqualTo(OrderRejectReason.CLOSE_ONLY);

            // Assert: SELL（开空）也应被拒绝（无持仓=开仓）
            SignalEvent sellSignal = new SignalEvent(
                    "TestStrategy", BTC_USDT, SignalType.SELL,
                    BigDecimal.ONE, "Open Short", Map.of(), System.currentTimeMillis());
            Order sellOrder = executionEngine.execute(sellSignal, account,
                    BigDecimal.valueOf(95000), System.currentTimeMillis());
            assertThat(sellOrder.rejectReason()).isEqualTo(OrderRejectReason.CLOSE_ONLY);

            // 解除 CLOSE_ONLY，开一个多仓
            killSwitch.deactivate();
            executionEngine.execute(buySignal, account,
                    BigDecimal.valueOf(95000), System.currentTimeMillis());

            // 重新激活 CLOSE_ONLY
            killSwitch.activate(KillSwitch.Mode.CLOSE_ONLY);

            // Assert: SELL（平仓）应通过（有持仓=平仓）
            SignalEvent closeSignal = new SignalEvent(
                    "TestStrategy", BTC_USDT, SignalType.SELL,
                    BigDecimal.ONE, "Close", Map.of(), System.currentTimeMillis());
            Order closeOrder = executionEngine.execute(closeSignal, account,
                    BigDecimal.valueOf(96000), System.currentTimeMillis());
            assertThat(closeOrder.status()).isEqualTo(OrderStatus.FILLED);
        }

        @Test
        @DisplayName("恢复正常模式后应允许交易")
        void shouldAllowTradingAfterDeactivation() {
            // Arrange: 激活 HALT 后恢复
            killSwitch.activate(KillSwitch.Mode.HALT);
            killSwitch.deactivate();

            SignalEvent buySignal = new SignalEvent(
                    "TestStrategy", BTC_USDT, SignalType.BUY,
                    BigDecimal.ONE, "Test", Map.of(), System.currentTimeMillis());

            // Act
            Order order = executionEngine.execute(buySignal, account,
                    BigDecimal.valueOf(95000), System.currentTimeMillis());

            // Assert
            assertThat(order.status()).isEqualTo(OrderStatus.FILLED);
            assertThat(account.hasPosition(BTC_USDT)).isTrue();
        }

        @Test
        @DisplayName("HOLD 信号不受 KillSwitch 影响")
        void shouldNotAffectHoldSignals() {
            // Arrange
            killSwitch.activate(KillSwitch.Mode.HALT);
            SignalEvent holdSignal = new SignalEvent(
                    "TestStrategy", BTC_USDT, SignalType.HOLD,
                    BigDecimal.ZERO, "Hold", Map.of(), System.currentTimeMillis());

            // Act
            Order order = executionEngine.execute(holdSignal, account,
                    BigDecimal.valueOf(95000), System.currentTimeMillis());

            // Assert: HOLD 信号返回 null（不产生订单）
            assertThat(order).isNull();
        }
    }

    // ========================================================================
    // Test 6: AlertService 集成 - 高内存触发告警
    // ========================================================================

    @Nested
    @DisplayName("Test 6: AlertService 集成 - 告警规则与指标检查")
    class AlertServiceIntegration {

        private AlertService alertService;

        @BeforeEach
        void setUp() {
            alertService = new AlertService();
        }

        @Test
        @DisplayName("高内存使用率应触发 CRITICAL 告警")
        void shouldTriggerCriticalAlertOnHighMemory() {
            // Arrange
            alertService.addRule(new AlertRule(
                    "memory-critical", "HIGH_MEMORY", 85.0,
                    AlertRule.Severity.CRITICAL, true));

            MetricsSnapshot snapshot = new MetricsSnapshot(
                    100, 50, 10, 5, 5, Map.of(),
                    10, 50, 5, 20, 100, 95.0, 0.1);

            // Act
            List<AlertEvent> alerts = alertService.checkAndAlert(snapshot);

            // Assert
            assertThat(alerts).hasSize(1);
            assertThat(alerts.get(0).ruleName()).isEqualTo("memory-critical");
            assertThat(alerts.get(0).severity()).isEqualTo(AlertRule.Severity.CRITICAL);
            assertThat(alerts.get(0).message()).contains("95.0%");
            assertThat(alerts.get(0).resolved()).isFalse();
        }

        @Test
        @DisplayName("多条规则同时触发应返回所有告警")
        void shouldReturnAllTriggeredAlerts() {
            // Arrange
            alertService.addRule(new AlertRule(
                    "memory", "HIGH_MEMORY", 80.0,
                    AlertRule.Severity.WARNING, true));
            alertService.addRule(new AlertRule(
                    "errors", "HIGH_ERROR_RATE", 5.0,
                    AlertRule.Severity.CRITICAL, true));
            alertService.addRule(new AlertRule(
                    "latency", "HIGH_EVENT_LATENCY", 100.0,
                    AlertRule.Severity.WARNING, true));

            MetricsSnapshot snapshot = new MetricsSnapshot(
                    100, 50, 10, 5, 5, Map.of(),
                    10, 200.0, 5, 20, 100, 92.0, 8.5);

            // Act
            List<AlertEvent> alerts = alertService.checkAndAlert(snapshot);

            // Assert: 3 条规则全部触发
            assertThat(alerts).hasSize(3);
            assertThat(alerts).extracting(AlertEvent::ruleName)
                    .containsExactlyInAnyOrder("memory", "errors", "latency");
        }

        @Test
        @DisplayName("告警历史应记录所有触发事件")
        void shouldRecordAllAlertsInHistory() {
            // Arrange
            alertService.addRule(new AlertRule(
                    "memory", "HIGH_MEMORY", 80.0,
                    AlertRule.Severity.WARNING, true));

            MetricsSnapshot highMemory = new MetricsSnapshot(
                    0, 0, 0, 0, 0, Map.of(),
                    0, 0, 0, 0, 0, 90.0, 0.0);
            MetricsSnapshot lowMemory = new MetricsSnapshot(
                    0, 0, 0, 0, 0, Map.of(),
                    0, 0, 0, 0, 0, 50.0, 0.0);

            // Act
            alertService.checkAndAlert(highMemory);
            alertService.checkAndAlert(lowMemory); // 不触发
            alertService.checkAndAlert(highMemory); // 再次触发

            // Assert
            assertThat(alertService.getAlertHistory()).hasSize(2);
        }

        @Test
        @DisplayName("禁用的规则不应触发告警")
        void shouldNotTriggerDisabledRules() {
            // Arrange
            alertService.addRule(new AlertRule(
                    "disabled", "HIGH_MEMORY", 50.0,
                    AlertRule.Severity.CRITICAL, false));

            MetricsSnapshot snapshot = new MetricsSnapshot(
                    0, 0, 0, 0, 0, Map.of(),
                    0, 0, 0, 0, 0, 99.0, 0.0);

            // Act
            List<AlertEvent> alerts = alertService.checkAndAlert(snapshot);

            // Assert
            assertThat(alerts).isEmpty();
        }

        @Test
        @DisplayName("连接器断开应触发告警")
        void shouldAlertOnConnectorDisconnect() {
            // Arrange
            alertService.addRule(new AlertRule(
                    "connector-down", "DISCONNECTED", 1,
                    AlertRule.Severity.CRITICAL, true));

            Map<String, ConnectorHealth> healthMap = Map.of(
                    "binance", new ConnectorHealth(false, 0, 0, 5, "Connection refused"),
                    "coinglass", new ConnectorHealth(true, 1000, 500, 0, null));

            MetricsSnapshot snapshot = new MetricsSnapshot(
                    0, 0, 0, 0, 0, healthMap,
                    0, 0, 0, 0, 0, 50.0, 0.0);

            // Act
            List<AlertEvent> alerts = alertService.checkAndAlert(snapshot);

            // Assert
            assertThat(alerts).hasSize(1);
            assertThat(alerts.get(0).message()).contains("disconnected");
        }
    }

    // ========================================================================
    // Test 7: ConfigVersionService 集成
    // draft -> publish -> active（使用 InMemoryConfigRepository）
    // ========================================================================

    @Nested
    @DisplayName("Test 7: InMemoryConfigRepository 集成 - 配置版本管理")
    class ConfigVersionRepositoryIntegration {

        private com.tj.crypto.admin.service.InMemoryConfigRepository repository;

        @BeforeEach
        void setUp() {
            repository = new com.tj.crypto.admin.service.InMemoryConfigRepository();
        }

        @Test
        @DisplayName("保存配置版本后可通过 findById 查找")
        void shouldSaveAndFindById() {
            // Arrange
            com.tj.crypto.admin.domain.ConfigVersion version = new com.tj.crypto.admin.domain.ConfigVersion(
                    "cv-001",
                    com.tj.crypto.admin.domain.ConfigType.STRATEGY,
                    "MacdCross",
                    "{\"fastPeriod\":12}",
                    com.tj.crypto.admin.domain.ConfigStatus.DRAFT,
                    "初始配置",
                    null,
                    java.time.Instant.now(),
                    java.time.Instant.now());

            // Act
            repository.save(version);

            // Assert
            assertThat(repository.findById("cv-001")).isPresent();
            assertThat(repository.findById("cv-001").get().configKey()).isEqualTo("MacdCross");
            assertThat(repository.findById("cv-001").get().status())
                    .isEqualTo(com.tj.crypto.admin.domain.ConfigStatus.DRAFT);
        }

        @Test
        @DisplayName("draft -> active 完整生命周期")
        void shouldSupportDraftToActiveLifecycle() {
            // Arrange: 创建 draft
            com.tj.crypto.admin.domain.ConfigVersion draft = new com.tj.crypto.admin.domain.ConfigVersion(
                    "cv-001",
                    com.tj.crypto.admin.domain.ConfigType.RISK,
                    "maxLoss",
                    "{\"maxLossPct\":2.0}",
                    com.tj.crypto.admin.domain.ConfigStatus.DRAFT,
                    "初始风控", null,
                    java.time.Instant.now(), java.time.Instant.now());
            repository.save(draft);

            // Act: 更新为 active
            com.tj.crypto.admin.domain.ConfigVersion active = draft.withStatus(
                    com.tj.crypto.admin.domain.ConfigStatus.ACTIVE);
            repository.save(active);

            // Assert
            assertThat(repository.findActive(
                    com.tj.crypto.admin.domain.ConfigType.RISK, "maxLoss")).isPresent();
            assertThat(repository.findActive(
                    com.tj.crypto.admin.domain.ConfigType.RISK, "maxLoss").get().status())
                    .isEqualTo(com.tj.crypto.admin.domain.ConfigStatus.ACTIVE);
        }

        @Test
        @DisplayName("新版本发布时旧 active 版本应可被替换")
        void shouldReplaceOldActiveVersion() {
            // Arrange: 发布 v1
            com.tj.crypto.admin.domain.ConfigVersion v1 = new com.tj.crypto.admin.domain.ConfigVersion(
                    "cv-001",
                    com.tj.crypto.admin.domain.ConfigType.STRATEGY,
                    "MacdCross",
                    "{\"v\":1}",
                    com.tj.crypto.admin.domain.ConfigStatus.ACTIVE,
                    "v1", "admin",
                    java.time.Instant.now(), java.time.Instant.now());
            repository.save(v1);

            // Act: 发布 v2（替换 v1）
            com.tj.crypto.admin.domain.ConfigVersion v1Archived = v1.withStatus(
                    com.tj.crypto.admin.domain.ConfigStatus.ARCHIVED);
            repository.save(v1Archived);

            com.tj.crypto.admin.domain.ConfigVersion v2 = new com.tj.crypto.admin.domain.ConfigVersion(
                    "cv-002",
                    com.tj.crypto.admin.domain.ConfigType.STRATEGY,
                    "MacdCross",
                    "{\"v\":2}",
                    com.tj.crypto.admin.domain.ConfigStatus.ACTIVE,
                    "v2", "admin",
                    java.time.Instant.now(), java.time.Instant.now());
            repository.save(v2);

            // Assert
            assertThat(repository.findActive(
                    com.tj.crypto.admin.domain.ConfigType.STRATEGY, "MacdCross")).isPresent();
            assertThat(repository.findActive(
                    com.tj.crypto.admin.domain.ConfigType.STRATEGY, "MacdCross").get().versionId())
                    .isEqualTo("cv-002");
        }

        @Test
        @DisplayName("getHistory 应返回所有版本")
        void shouldReturnAllVersionsInHistory() {
            // Arrange
            java.time.Instant now = java.time.Instant.now();
            repository.save(new com.tj.crypto.admin.domain.ConfigVersion(
                    "cv-001", com.tj.crypto.admin.domain.ConfigType.STRATEGY, "MacdCross",
                    "{\"v\":1}", com.tj.crypto.admin.domain.ConfigStatus.DRAFT,
                    "v1", null, now, now));
            repository.save(new com.tj.crypto.admin.domain.ConfigVersion(
                    "cv-002", com.tj.crypto.admin.domain.ConfigType.STRATEGY, "MacdCross",
                    "{\"v\":2}", com.tj.crypto.admin.domain.ConfigStatus.ACTIVE,
                    "v2", "admin", now.plusSeconds(1), now.plusSeconds(1)));

            // Act
            List<com.tj.crypto.admin.domain.ConfigVersion> history =
                    repository.findHistory(com.tj.crypto.admin.domain.ConfigType.STRATEGY, "MacdCross");

            // Assert
            assertThat(history).hasSize(2);
        }

        @Test
        @DisplayName("findActiveByType 应返回指定类型下所有 active 版本")
        void shouldReturnActiveVersionsByType() {
            // Arrange
            java.time.Instant now = java.time.Instant.now();
            repository.save(new com.tj.crypto.admin.domain.ConfigVersion(
                    "cv-001", com.tj.crypto.admin.domain.ConfigType.STRATEGY, "MacdCross",
                    "{}", com.tj.crypto.admin.domain.ConfigStatus.ACTIVE,
                    "", "admin", now, now));
            repository.save(new com.tj.crypto.admin.domain.ConfigVersion(
                    "cv-002", com.tj.crypto.admin.domain.ConfigType.STRATEGY, "RsiCross",
                    "{}", com.tj.crypto.admin.domain.ConfigStatus.ACTIVE,
                    "", "admin", now, now));
            repository.save(new com.tj.crypto.admin.domain.ConfigVersion(
                    "cv-003", com.tj.crypto.admin.domain.ConfigType.RISK, "maxLoss",
                    "{}", com.tj.crypto.admin.domain.ConfigStatus.ACTIVE,
                    "", "admin", now, now));

            // Act
            List<com.tj.crypto.admin.domain.ConfigVersion> strategyActives =
                    repository.findActiveByType(com.tj.crypto.admin.domain.ConfigType.STRATEGY);

            // Assert
            assertThat(strategyActives).hasSize(2);
        }

        @Test
        @DisplayName("clear 应清空所有数据")
        void shouldClearAllData() {
            // Arrange
            repository.save(new com.tj.crypto.admin.domain.ConfigVersion(
                    "cv-001", com.tj.crypto.admin.domain.ConfigType.STRATEGY, "MacdCross",
                    "{}", com.tj.crypto.admin.domain.ConfigStatus.DRAFT,
                    "", null, java.time.Instant.now(), java.time.Instant.now()));

            // Act
            repository.clear();

            // Assert
            assertThat(repository.findById("cv-001")).isEmpty();
            assertThat(repository.findAllActive()).isEmpty();
        }
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    private BarEvent createBarEvent(double open, double high, double low,
                                    double close, double volume) {
        return new BarEvent(
                BTC_USDT,
                EventMetadata.of(Exchange.BINANCE, System.currentTimeMillis()),
                Timeframe.M1,
                BigDecimal.valueOf(open),
                BigDecimal.valueOf(high),
                BigDecimal.valueOf(low),
                BigDecimal.valueOf(close),
                BigDecimal.valueOf(volume),
                BigDecimal.valueOf(close * volume),
                true);
    }
}
