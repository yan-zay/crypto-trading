package com.tj.crypto.event;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.EventMetadata;
import com.tj.crypto.marketdata.model.LiquidationEvent;
import com.tj.crypto.marketdata.model.MarketEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * InMemoryEventBus 单元测试。
 * 验证发布/订阅、类型过滤、异常隔离。
 */
class InMemoryEventBusTest {

    private InMemoryEventBus eventBus;

    @BeforeEach
    void setUp() {
        eventBus = new InMemoryEventBus();
    }

    private BarEvent createBarEvent(String symbol) {
        Instrument instrument = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, symbol);
        EventMetadata metadata = EventMetadata.of(Exchange.BINANCE, System.currentTimeMillis());
        return new BarEvent(instrument, metadata, Timeframe.M1,
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.valueOf(5),
                BigDecimal.valueOf(100), BigDecimal.valueOf(500), true);
    }

    private LiquidationEvent createLiquidationEvent(String symbol) {
        Instrument instrument = Instrument.of(Exchange.COINGLASS, MarketType.PERPETUAL, symbol);
        EventMetadata metadata = EventMetadata.of(Exchange.COINGLASS, System.currentTimeMillis());
        return new LiquidationEvent(instrument, metadata, OrderSide.LONG,
                BigDecimal.valueOf(16000), BigDecimal.ONE, BigDecimal.valueOf(500000), "Binance");
    }

    @Nested
    @DisplayName("基本发布/订阅")
    class BasicPubSub {

        @Test
        @DisplayName("订阅者应收到发布的事件")
        void shouldReceivePublishedEvent() {
            List<BarEvent> received = new ArrayList<>();
            eventBus.subscribe(BarEvent.class, received::add);

            BarEvent event = createBarEvent("BTCUSDT");
            eventBus.publish(event);

            assertThat(received).hasSize(1);
            assertThat(received.get(0)).isSameAs(event);
        }

        @Test
        @DisplayName("多个订阅者应都收到事件")
        void shouldDeliverToMultipleSubscribers() {
            AtomicInteger count1 = new AtomicInteger();
            AtomicInteger count2 = new AtomicInteger();

            eventBus.subscribe(BarEvent.class, e -> count1.incrementAndGet());
            eventBus.subscribe(BarEvent.class, e -> count2.incrementAndGet());

            eventBus.publish(createBarEvent("BTCUSDT"));

            assertThat(count1.get()).isEqualTo(1);
            assertThat(count2.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("同一事件类型应收到所有发布")
        void shouldReceiveAllPublishedEvents() {
            List<BarEvent> received = new ArrayList<>();
            eventBus.subscribe(BarEvent.class, received::add);

            eventBus.publish(createBarEvent("BTCUSDT"));
            eventBus.publish(createBarEvent("ETHUSDT"));

            assertThat(received).hasSize(2);
        }
    }

    @Nested
    @DisplayName("类型过滤")
    class TypeFiltering {

        @Test
        @DisplayName("BarEvent 订阅者不应收到 LiquidationEvent")
        void shouldNotReceiveOtherEventType() {
            List<BarEvent> barEvents = new ArrayList<>();
            eventBus.subscribe(BarEvent.class, barEvents::add);

            eventBus.publish(createLiquidationEvent("BTCUSDT"));

            assertThat(barEvents).isEmpty();
        }

        @Test
        @DisplayName("不同事件类型可独立订阅")
        void shouldSupportMultipleEventTypes() {
            List<BarEvent> barEvents = new ArrayList<>();
            List<LiquidationEvent> liqEvents = new ArrayList<>();

            eventBus.subscribe(BarEvent.class, barEvents::add);
            eventBus.subscribe(LiquidationEvent.class, liqEvents::add);

            eventBus.publish(createBarEvent("BTCUSDT"));
            eventBus.publish(createLiquidationEvent("BTCUSDT"));

            assertThat(barEvents).hasSize(1);
            assertThat(liqEvents).hasSize(1);
        }

        @Test
        @DisplayName("通用 MarketEvent 订阅者应收到所有事件类型")
        void shouldReceiveAllEventsWithMarketEventSubscription() {
            List<MarketEvent> allEvents = new ArrayList<>();
            eventBus.subscribe(MarketEvent.class, allEvents::add);

            eventBus.publish(createBarEvent("BTCUSDT"));
            eventBus.publish(createLiquidationEvent("BTCUSDT"));

            // MarketEvent 订阅者应收到所有子类型事件（事件总线向父类型传播）
            assertThat(allEvents).hasSize(2);
        }
    }

    @Nested
    @DisplayName("异常隔离")
    class ExceptionIsolation {

        @Test
        @DisplayName("单个处理器异常不应影响其他处理器")
        void shouldIsolateHandlerException() {
            AtomicInteger successCount = new AtomicInteger();

            eventBus.subscribe(BarEvent.class, e -> {
                throw new RuntimeException("Handler error");
            });
            eventBus.subscribe(BarEvent.class, e -> successCount.incrementAndGet());
            eventBus.subscribe(BarEvent.class, e -> successCount.incrementAndGet());

            eventBus.publish(createBarEvent("BTCUSDT"));

            // 第一个处理器抛异常，后两个应正常执行
            assertThat(successCount.get()).isEqualTo(2);
        }

        @Test
        @DisplayName("无订阅者时发布不应抛异常")
        void shouldNotThrowWhenNoSubscribers() {
            eventBus.publish(createBarEvent("BTCUSDT"));
            // 不抛异常即通过
        }
    }
}
