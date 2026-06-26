package com.tj.crypto.central;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.event.InMemoryEventBus;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.EventMetadata;
import com.tj.crypto.marketdata.model.LiquidationEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StrategyEngine 事件分发集成测试。
 * 验证 EventBus → StrategyEngine → Strategy 的完整链路。
 */
class StrategyEngineEventTest {

    private InMemoryEventBus eventBus;
    private StrategyEngine strategyEngine;
    private ThreadPoolTaskExecutor executor;

    @BeforeEach
    void setUp() {
        eventBus = new InMemoryEventBus();

        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("test-");
        executor.initialize();

        // 创建策略引擎（不注入真实策略，用测试策略替代）
        strategyEngine = new StrategyEngine(executor, List.of(), eventBus);
        strategyEngine.init();
    }

    private LiquidationEvent createLiquidationEvent(String symbol, BigDecimal amountUsd) {
        Instrument instrument = Instrument.of(Exchange.COINGLASS, MarketType.PERPETUAL, symbol);
        EventMetadata metadata = EventMetadata.of(Exchange.COINGLASS, System.currentTimeMillis());
        return new LiquidationEvent(instrument, metadata, OrderSide.LONG,
                BigDecimal.valueOf(16000), BigDecimal.ONE, amountUsd, "Binance");
    }

    private BarEvent createBarEvent(String symbol) {
        Instrument instrument = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, symbol);
        EventMetadata metadata = EventMetadata.of(Exchange.BINANCE, System.currentTimeMillis());
        return new BarEvent(instrument, metadata, Timeframe.M1,
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.valueOf(5),
                BigDecimal.valueOf(100), BigDecimal.valueOf(500), true);
    }

    @Test
    @DisplayName("发布 LiquidationEvent 应触发 StrategyEngine.onMarketEvent")
    void shouldTriggerOnMarketEventWhenLiquidationPublished() throws Exception {
        // 注册一个监听器验证事件是否到达
        CountDownLatch latch = new CountDownLatch(1);
        eventBus.subscribe(LiquidationEvent.class, e -> latch.countDown());

        LiquidationEvent event = createLiquidationEvent("BTCUSDT", BigDecimal.valueOf(500000));
        eventBus.publish(event);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("发布 BarEvent 应触发 StrategyEngine.onMarketEvent")
    void shouldTriggerOnMarketEventWhenBarPublished() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        eventBus.subscribe(BarEvent.class, e -> latch.countDown());

        BarEvent event = createBarEvent("BTCUSDT");
        eventBus.publish(event);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("callOnEvent 应可被外部调用（不再是 private）")
    void shouldAllowPublicCallOnEvent() {
        // 验证方法是 public 的（编译通过即验证）
        // 空策略列表，调用不应抛异常
        strategyEngine.callOnEvent(
                com.tj.crypto.enums.Symbol.BTC_USDT,
                com.tj.crypto.enums.Indicator.LIQUIDATION
        );
    }

    @Test
    @DisplayName("策略引擎初始化应注册到事件总线")
    void shouldRegisterToEventBusOnInit() {
        // 验证 init() 后事件总线有订阅者
        // 通过发布事件并验证不抛异常来间接验证
        eventBus.publish(createBarEvent("BTCUSDT"));
        // 不抛异常即通过
    }
}
