package com.tj.crypto.integration;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.event.InMemoryEventBus;
import com.tj.crypto.marketdata.model.EventMetadata;
import com.tj.crypto.marketdata.model.LiquidationEvent;
import com.tj.crypto.marketdata.model.MarketEvent;
import com.tj.crypto.strategy.core.InMemorySignalCollector;
import com.tj.crypto.strategy.core.SignalCollector;
import com.tj.crypto.strategy.core.SignalEvent;
import com.tj.crypto.strategy.core.StrategyContext;
import com.tj.crypto.strategy.impl.LiquidationSpikeStrategyV2;
import com.tj.crypto.central.StrategyEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 数据管线集成测试。
 * 验证完整链路：
 *   LiquidationEvent → InMemoryEventBus → StrategyEngine → Strategy.onEvent() → SignalCollector
 *
 * 测试场景：
 * 1. 大额爆仓（超过阈值）→ 策略应生成信号
 * 2. 小额爆仓（低于阈值）→ 策略不应生成信号
 * 3. 多个事件顺序处理 → 信号正确收集
 */
class DataPipelineTest {

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
        executor.setThreadNamePrefix("pipeline-test-");
        executor.initialize();

        StrategyContext context = mock(StrategyContext.class);

        // 创建真实的策略实例
        LiquidationSpikeStrategyV2 strategy = new LiquidationSpikeStrategyV2();
        strategy.setThresholdUsd(new BigDecimal("1000000"));

        // 创建策略引擎，注入真实策略和信号收集器
        strategyEngine = new StrategyEngine(
                executor, eventBus, context, signalCollector, List.of(strategy));
        strategyEngine.init();
    }

    private LiquidationEvent createLiquidationEvent(String symbol, BigDecimal amountUsd, OrderSide side) {
        Instrument instrument = Instrument.of(Exchange.COINGLASS, MarketType.PERPETUAL, symbol);
        EventMetadata metadata = EventMetadata.of(Exchange.COINGLASS, System.currentTimeMillis());
        return new LiquidationEvent(
                instrument, metadata, side,
                BigDecimal.valueOf(95000), BigDecimal.TEN, amountUsd, "Binance");
    }

    @Test
    @DisplayName("大额爆仓事件应通过管线生成交易信号")
    void shouldGenerateSignalWhenLargeLiquidationPublished() throws Exception {
        // Arrange: 创建一个超过阈值（100万 USD）的爆仓事件
        LiquidationEvent event = createLiquidationEvent(
                "BTCUSDT", BigDecimal.valueOf(2_000_000), OrderSide.LONG);

        // 使用 CountDownLatch 等待异步策略执行完成
        CountDownLatch latch = new CountDownLatch(1);
        eventBus.subscribe(LiquidationEvent.class, e -> latch.countDown());

        // Act: 发布事件到事件总线
        eventBus.publish(event);

        // 等待事件传播和策略执行
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        // 等待策略引擎异步执行完成
        Thread.sleep(500);

        // Assert: 验证信号被收集
        List<SignalEvent> signals = signalCollector.getSignals("LiquidationSpike");
        assertThat(signals).hasSize(1);

        SignalEvent signal = signals.get(0);
        assertThat(signal.strategyName()).isEqualTo("LiquidationSpike");
        assertThat(signal.instrument().symbol()).isEqualTo("BTCUSDT");
        assertThat(signal.reason()).contains("2000000");
    }

    @Test
    @DisplayName("小额爆仓事件不应生成交易信号")
    void shouldNotGenerateSignalWhenSmallLiquidationPublished() throws Exception {
        // Arrange: 创建一个低于阈值（100万 USD）的爆仓事件
        LiquidationEvent event = createLiquidationEvent(
                "BTCUSDT", BigDecimal.valueOf(500_000), OrderSide.SHORT);

        CountDownLatch latch = new CountDownLatch(1);
        eventBus.subscribe(LiquidationEvent.class, e -> latch.countDown());

        // Act
        eventBus.publish(event);

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(500);

        // Assert: 无信号
        List<SignalEvent> signals = signalCollector.getSignals("LiquidationSpike");
        assertThat(signals).isEmpty();
    }

    @Test
    @DisplayName("多个大额爆仓事件应生成对应数量的信号")
    void shouldGenerateMultipleSignalsForMultipleLargeLiquidations() throws Exception {
        // Arrange: 发布多个大额爆仓
        LiquidationEvent event1 = createLiquidationEvent(
                "BTCUSDT", BigDecimal.valueOf(3_000_000), OrderSide.LONG);
        LiquidationEvent event2 = createLiquidationEvent(
                "ETHUSDT", BigDecimal.valueOf(5_000_000), OrderSide.SHORT);
        LiquidationEvent event3 = createLiquidationEvent(
                "BTCUSDT", BigDecimal.valueOf(1_500_000), OrderSide.LONG);

        CountDownLatch latch = new CountDownLatch(3);
        eventBus.subscribe(LiquidationEvent.class, e -> latch.countDown());

        // Act
        eventBus.publish(event1);
        eventBus.publish(event2);
        eventBus.publish(event3);

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(800);

        // Assert: 三个信号
        List<SignalEvent> signals = signalCollector.getSignals("LiquidationSpike");
        assertThat(signals).hasSize(3);
    }

    @Test
    @DisplayName("事件总线应将 LiquidationEvent 分发到 MarketEvent 订阅者（父类型传播）")
    void shouldDispatchLiquidationEventToMarketEventSubscribers() throws Exception {
        // Arrange: 订阅父类型 MarketEvent
        CountDownLatch latch = new CountDownLatch(1);
        eventBus.subscribe(MarketEvent.class, e -> latch.countDown());

        LiquidationEvent event = createLiquidationEvent(
                "BTCUSDT", BigDecimal.valueOf(1_000_000), OrderSide.LONG);

        // Act
        eventBus.publish(event);

        // Assert: MarketEvent 订阅者应收到 LiquidationEvent
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("管线中策略异常不应影响事件总线的后续事件处理")
    void shouldNotBreakPipelineWhenStrategyThrowsException() throws Exception {
        // Arrange: 创建一个会抛异常的策略和正常策略
        InMemoryEventBus localEventBus = new InMemoryEventBus();
        InMemorySignalCollector localCollector = new InMemorySignalCollector();

        ThreadPoolTaskExecutor localExecutor = new ThreadPoolTaskExecutor();
        localExecutor.setCorePoolSize(2);
        localExecutor.setMaxPoolSize(4);
        localExecutor.setQueueCapacity(10);
        localExecutor.setThreadNamePrefix("pipeline-err-test-");
        localExecutor.initialize();

        StrategyContext context = mock(StrategyContext.class);

        // 故障策略
        LiquidationSpikeStrategyV2 failingStrategy = new LiquidationSpikeStrategyV2() {
            @Override
            public com.tj.crypto.strategy.core.SignalEvent onEvent(
                    com.tj.crypto.marketdata.model.MarketEvent event,
                    com.tj.crypto.strategy.core.StrategyContext ctx) {
                throw new RuntimeException("Strategy error");
            }
        };

        // 正常策略
        LiquidationSpikeStrategyV2 normalStrategy = new LiquidationSpikeStrategyV2();
        normalStrategy.setThresholdUsd(new BigDecimal("1000000"));

        StrategyEngine localEngine = new StrategyEngine(
                localExecutor, localEventBus, context, localCollector,
                List.of(failingStrategy, normalStrategy));
        localEngine.init();

        LiquidationEvent event = createLiquidationEvent(
                "BTCUSDT", BigDecimal.valueOf(2_000_000), OrderSide.LONG);

        CountDownLatch latch = new CountDownLatch(1);
        localEventBus.subscribe(LiquidationEvent.class, e -> latch.countDown());

        // Act
        localEventBus.publish(event);

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(800);

        // Assert: 正常策略的信号应被收集，尽管故障策略抛了异常
        List<SignalEvent> signals = localCollector.getSignals("LiquidationSpike");
        assertThat(signals).hasSize(1);

        localExecutor.shutdown();
    }
}
