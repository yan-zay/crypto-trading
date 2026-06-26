package com.tj.crypto.integration;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.event.InMemoryEventBus;
import com.tj.crypto.factor.cache.BarCache;
import com.tj.crypto.factor.cache.InMemoryBarCache;
import com.tj.crypto.factor.core.Factor;
import com.tj.crypto.factor.core.FactorCalculator;
import com.tj.crypto.factor.core.FactorRegistry;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.EventMetadata;
import com.tj.crypto.strategy.core.SignalCollector;
import com.tj.crypto.strategy.core.SignalEvent;
import com.tj.crypto.strategy.core.StrategyContext;
import com.tj.crypto.strategy.impl.MacdCrossStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 多交易对并行集成测试。
 * 验证 BTCUSDT、ETHUSDT、SOLUSDT 三个交易对同时处理时：
 * 1. InMemoryBarCache 按交易对独立缓存
 * 2. MacdCrossStrategy 对每个交易对独立计算信号
 * 3. 不同交易对的信号互不干扰
 * 4. FactorRegistry 对每个交易对独立计算因子
 */
class MultiPairIntegrationTest {

    private InMemoryEventBus eventBus;
    private InMemoryBarCache barCache;
    private Instrument btcUsdt;
    private Instrument ethUsdt;
    private Instrument solUsdt;

    @BeforeEach
    void setUp() {
        eventBus = new InMemoryEventBus();
        barCache = new InMemoryBarCache(eventBus);
        barCache.init();

        btcUsdt = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
        ethUsdt = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "ETHUSDT");
        solUsdt = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "SOLUSDT");
    }

    private BarEvent createBar(Instrument instrument, long timestamp, double close) {
        EventMetadata metadata = EventMetadata.of(Exchange.BINANCE, timestamp);
        return new BarEvent(instrument, metadata, Timeframe.M1,
                BigDecimal.valueOf(close - 1), BigDecimal.valueOf(close + 1),
                BigDecimal.valueOf(close - 2), BigDecimal.valueOf(close),
                BigDecimal.valueOf(100), BigDecimal.valueOf(close * 100), true);
    }

    @Test
    @DisplayName("三个交易对的 BarEvent 应被 InMemoryBarCache 独立缓存")
    void shouldCacheBarEventsIndependentlyForEachPair() throws Exception {
        // Arrange
        BarEvent btcBar = createBar(btcUsdt, 1000L, 95000);
        BarEvent ethBar = createBar(ethUsdt, 1000L, 3500);
        BarEvent solBar = createBar(solUsdt, 1000L, 180);

        CountDownLatch latch = new CountDownLatch(3);
        eventBus.subscribe(BarEvent.class, e -> latch.countDown());

        // Act
        eventBus.publish(btcBar);
        eventBus.publish(ethBar);
        eventBus.publish(solBar);

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        // Assert: 每个交易对应有独立缓存
        assertThat(barCache.size(btcUsdt, Timeframe.M1)).isEqualTo(1);
        assertThat(barCache.size(ethUsdt, Timeframe.M1)).isEqualTo(1);
        assertThat(barCache.size(solUsdt, Timeframe.M1)).isEqualTo(1);

        // 验证缓存内容正确
        List<BarEvent> btcBars = barCache.getBars(btcUsdt, Timeframe.M1, 10);
        assertThat(btcBars).hasSize(1);
        assertThat(btcBars.get(0).close()).isEqualByComparingTo(BigDecimal.valueOf(95000));

        List<BarEvent> ethBars = barCache.getBars(ethUsdt, Timeframe.M1, 10);
        assertThat(ethBars).hasSize(1);
        assertThat(ethBars.get(0).close()).isEqualByComparingTo(BigDecimal.valueOf(3500));

        List<BarEvent> solBars = barCache.getBars(solUsdt, Timeframe.M1, 10);
        assertThat(solBars).hasSize(1);
        assertThat(solBars.get(0).close()).isEqualByComparingTo(BigDecimal.valueOf(180));
    }

    @Test
    @DisplayName("MacdCrossStrategy 应对每个交易对独立计算信号，互不干扰")
    void shouldComputeSignalsIndependentlyForEachPair() {
        // Arrange
        MacdCrossStrategy strategy = new MacdCrossStrategy();
        StrategyContext context = mock(StrategyContext.class);

        // BTC: MACD_HIST 从负变正 -> 金叉 -> BUY
        // ETH: MACD_HIST 从正变负 -> 死叉 -> SELL
        // SOL: MACD_HIST 保持正 -> 无信号
        when(context.getFactor(eq("MACD_HIST"), eq(btcUsdt), any()))
                .thenReturn(Factor.of("MACD_HIST", BigDecimal.valueOf(-0.5), 1000L))
                .thenReturn(Factor.of("MACD_HIST", BigDecimal.valueOf(0.3), 2000L));
        when(context.getFactor(eq("MACD_HIST"), eq(ethUsdt), any()))
                .thenReturn(Factor.of("MACD_HIST", BigDecimal.valueOf(0.8), 1000L))
                .thenReturn(Factor.of("MACD_HIST", BigDecimal.valueOf(-0.2), 2000L));
        when(context.getFactor(eq("MACD_HIST"), eq(solUsdt), any()))
                .thenReturn(Factor.of("MACD_HIST", BigDecimal.valueOf(0.5), 1000L))
                .thenReturn(Factor.of("MACD_HIST", BigDecimal.valueOf(0.7), 2000L));

        // Act: 第一轮 - 初始化 lastHistogram
        EventMetadata meta1 = EventMetadata.of(Exchange.BINANCE, 1000L);
        strategy.onEvent(new BarEvent(btcUsdt, meta1, Timeframe.M1,
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.valueOf(5),
                BigDecimal.valueOf(100), BigDecimal.valueOf(500), true), context);
        strategy.onEvent(new BarEvent(ethUsdt, meta1, Timeframe.M1,
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.valueOf(5),
                BigDecimal.valueOf(100), BigDecimal.valueOf(500), true), context);
        strategy.onEvent(new BarEvent(solUsdt, meta1, Timeframe.M1,
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.valueOf(5),
                BigDecimal.valueOf(100), BigDecimal.valueOf(500), true), context);

        // Act: 第二轮 - 触发信号
        EventMetadata meta2 = EventMetadata.of(Exchange.BINANCE, 2000L);
        SignalEvent btcSignal = strategy.onEvent(
                new BarEvent(btcUsdt, meta2, Timeframe.M1,
                        BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.valueOf(5),
                        BigDecimal.valueOf(100), BigDecimal.valueOf(500), true), context);
        SignalEvent ethSignal = strategy.onEvent(
                new BarEvent(ethUsdt, meta2, Timeframe.M1,
                        BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.valueOf(5),
                        BigDecimal.valueOf(100), BigDecimal.valueOf(500), true), context);
        SignalEvent solSignal = strategy.onEvent(
                new BarEvent(solUsdt, meta2, Timeframe.M1,
                        BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.valueOf(5),
                        BigDecimal.valueOf(100), BigDecimal.valueOf(500), true), context);

        // Assert
        // BTC 金叉 -> BUY 信号
        assertThat(btcSignal).isNotNull();
        assertThat(btcSignal.instrument().symbol()).isEqualTo("BTCUSDT");
        assertThat(btcSignal.type().name()).isEqualTo("BUY");

        // ETH 死叉 -> SELL 信号
        assertThat(ethSignal).isNotNull();
        assertThat(ethSignal.instrument().symbol()).isEqualTo("ETHUSDT");
        assertThat(ethSignal.type().name()).isEqualTo("SELL");

        // SOL 无变化 -> 无信号
        assertThat(solSignal).isNull();
    }

    @Test
    @DisplayName("多个交易对同时添加 bar 后，缓存大小各自独立")
    void shouldMaintainIndependentCacheSizeForEachPair() {
        // Arrange & Act: 每个交易对添加不同数量的 bar
        for (int i = 0; i < 5; i++) {
            barCache.addBar(createBar(btcUsdt, i * 60000L, 95000 + i));
        }
        for (int i = 0; i < 3; i++) {
            barCache.addBar(createBar(ethUsdt, i * 60000L, 3500 + i));
        }
        for (int i = 0; i < 8; i++) {
            barCache.addBar(createBar(solUsdt, i * 60000L, 180 + i));
        }

        // Assert: 各自独立
        assertThat(barCache.size(btcUsdt, Timeframe.M1)).isEqualTo(5);
        assertThat(barCache.size(ethUsdt, Timeframe.M1)).isEqualTo(3);
        assertThat(barCache.size(solUsdt, Timeframe.M1)).isEqualTo(8);
    }

    @Test
    @DisplayName("FactorRegistry 应对每个交易对独立计算因子")
    void shouldCalculateFactorsIndependentlyForEachPair() {
        // Arrange: mock 因子计算器，对不同交易对返回不同值
        FactorCalculator calculator = mock(FactorCalculator.class);
        when(calculator.name()).thenReturn("MACD_HIST");
        when(calculator.calculate(eq(btcUsdt), any()))
                .thenReturn(Factor.of("MACD_HIST", BigDecimal.valueOf(1.5), 1000L));
        when(calculator.calculate(eq(ethUsdt), any()))
                .thenReturn(Factor.of("MACD_HIST", BigDecimal.valueOf(-0.8), 1000L));
        when(calculator.calculate(eq(solUsdt), any()))
                .thenReturn(Factor.of("MACD_HIST", BigDecimal.valueOf(0.2), 1000L));

        FactorRegistry registry = new FactorRegistry(List.of(calculator));

        // Act
        Factor btcFactor = registry.calculate("MACD_HIST", btcUsdt, Timeframe.M1);
        Factor ethFactor = registry.calculate("MACD_HIST", ethUsdt, Timeframe.M1);
        Factor solFactor = registry.calculate("MACD_HIST", solUsdt, Timeframe.M1);

        // Assert: 每个交易对应有独立的因子值
        assertThat(btcFactor).isNotNull();
        assertThat(btcFactor.value()).isEqualByComparingTo(BigDecimal.valueOf(1.5));

        assertThat(ethFactor).isNotNull();
        assertThat(ethFactor.value()).isEqualByComparingTo(BigDecimal.valueOf(-0.8));

        assertThat(solFactor).isNotNull();
        assertThat(solFactor.value()).isEqualByComparingTo(BigDecimal.valueOf(0.2));
    }

    @Test
    @DisplayName("交易对之间不应互相污染 lastHistogram 状态")
    void shouldNotPolluteLastHistogramAcrossPairs() {
        // Arrange
        MacdCrossStrategy strategy = new MacdCrossStrategy();
        StrategyContext context = mock(StrategyContext.class);

        // BTC: 金叉（负->正）应产生信号
        // SOL: 同样的变化也应产生独立信号
        when(context.getFactor(eq("MACD_HIST"), eq(btcUsdt), any()))
                .thenReturn(Factor.of("MACD_HIST", BigDecimal.valueOf(-1.0), 1000L))
                .thenReturn(Factor.of("MACD_HIST", BigDecimal.valueOf(0.5), 2000L));
        when(context.getFactor(eq("MACD_HIST"), eq(solUsdt), any()))
                .thenReturn(Factor.of("MACD_HIST", BigDecimal.valueOf(-2.0), 1000L))
                .thenReturn(Factor.of("MACD_HIST", BigDecimal.valueOf(1.0), 2000L));

        // Act: 第一轮
        EventMetadata meta1 = EventMetadata.of(Exchange.BINANCE, 1000L);
        strategy.onEvent(new BarEvent(btcUsdt, meta1, Timeframe.M1,
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.valueOf(5),
                BigDecimal.valueOf(100), BigDecimal.valueOf(500), true), context);
        strategy.onEvent(new BarEvent(solUsdt, meta1, Timeframe.M1,
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.valueOf(5),
                BigDecimal.valueOf(100), BigDecimal.valueOf(500), true), context);

        // 第二轮
        EventMetadata meta2 = EventMetadata.of(Exchange.BINANCE, 2000L);
        SignalEvent btcSignal = strategy.onEvent(
                new BarEvent(btcUsdt, meta2, Timeframe.M1,
                        BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.valueOf(5),
                        BigDecimal.valueOf(100), BigDecimal.valueOf(500), true), context);
        SignalEvent solSignal = strategy.onEvent(
                new BarEvent(solUsdt, meta2, Timeframe.M1,
                        BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.valueOf(5),
                        BigDecimal.valueOf(100), BigDecimal.valueOf(500), true), context);

        // Assert: 两个交易对都应独立产生金叉信号
        assertThat(btcSignal).isNotNull();
        assertThat(btcSignal.instrument().symbol()).isEqualTo("BTCUSDT");
        assertThat(btcSignal.type().name()).isEqualTo("BUY");

        assertThat(solSignal).isNotNull();
        assertThat(solSignal.instrument().symbol()).isEqualTo("SOLUSDT");
        assertThat(solSignal.type().name()).isEqualTo("BUY");
    }

    @Test
    @DisplayName("事件总线应将三个交易对的 BarEvent 正确分发到订阅者")
    void shouldDispatchBarEventsForAllPairsToSubscribers() throws Exception {
        // Arrange
        CountDownLatch latch = new CountDownLatch(3);
        eventBus.subscribe(BarEvent.class, e -> latch.countDown());

        // Act
        eventBus.publish(createBar(btcUsdt, 1000L, 95000));
        eventBus.publish(createBar(ethUsdt, 1000L, 3500));
        eventBus.publish(createBar(solUsdt, 1000L, 180));

        // Assert
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    }
}
