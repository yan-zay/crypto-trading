package com.tj.crypto.client;

import com.tj.crypto.event.InMemoryEventBus;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.MarketEvent;
import com.tj.crypto.marketdata.normalize.BinanceKlineNormalizer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OkHttpBinanceWebSocketClient 单元测试。
 * 测试 JSON 解析和事件发布逻辑，不依赖真实网络连接。
 */
class OkHttpBinanceWebSocketClientTest {

    private OkHttpBinanceWebSocketClient client;
    private InMemoryEventBus eventBus;
    private final AtomicReference<MarketEvent> receivedEvent = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        eventBus = new InMemoryEventBus();
        BinanceKlineNormalizer normalizer = new BinanceKlineNormalizer();
        OkHttpClient okHttpClient = new OkHttpClient();
        List<String> symbols = List.of("BTCUSDT", "ETHUSDT");

        client = new OkHttpBinanceWebSocketClient(okHttpClient, normalizer, eventBus, symbols);

        // 订阅 BarEvent
        eventBus.subscribe(BarEvent.class, receivedEvent::set);
    }

    @Nested
    @DisplayName("kline 消息处理")
    class KlineMessageHandling {

        @Test
        @DisplayName("应正确解析 kline JSON 并发布 BarEvent")
        void shouldParseKlineAndPublishBarEvent() {
            String message = """
                    {
                        "e": "kline",
                        "E": 1672515782136,
                        "s": "BTCUSDT",
                        "k": {
                            "t": 1672515780000,
                            "T": 1672515839999,
                            "s": "BTCUSDT",
                            "i": "1m",
                            "o": "16721.50",
                            "h": "16722.00",
                            "l": "16721.00",
                            "c": "16721.50",
                            "v": "100.5",
                            "q": "1679231.25",
                            "x": false
                        }
                    }
                    """;

            client.handleMessage(message);

            assertThat(receivedEvent.get()).isNotNull();
            assertThat(receivedEvent.get()).isInstanceOf(BarEvent.class);
            BarEvent bar = (BarEvent) receivedEvent.get();
            assertThat(bar.instrument().symbol()).isEqualTo("BTCUSDT");
            assertThat(bar.instrument().baseAsset()).isEqualTo("BTC");
            assertThat(bar.open()).isEqualByComparingTo(new BigDecimal("16721.50"));
            assertThat(bar.high()).isEqualByComparingTo(new BigDecimal("16722.00"));
            assertThat(bar.low()).isEqualByComparingTo(new BigDecimal("16721.00"));
            assertThat(bar.close()).isEqualByComparingTo(new BigDecimal("16721.50"));
            assertThat(bar.volume()).isEqualByComparingTo(new BigDecimal("100.5"));
            assertThat(bar.closed()).isFalse();
        }

        @Test
        @DisplayName("应正确解析已关闭的 kline")
        void shouldParseClosedKline() {
            String message = """
                    {
                        "e": "kline",
                        "E": 1672515782136,
                        "s": "ETHUSDT",
                        "k": {
                            "t": 1672515780000,
                            "T": 1672515839999,
                            "s": "ETHUSDT",
                            "i": "1m",
                            "o": "1200.00",
                            "h": "1210.50",
                            "l": "1195.00",
                            "c": "1205.75",
                            "v": "500.25",
                            "q": "602500.50",
                            "x": true
                        }
                    }
                    """;

            client.handleMessage(message);

            assertThat(receivedEvent.get()).isNotNull();
            BarEvent bar = (BarEvent) receivedEvent.get();
            assertThat(bar.instrument().symbol()).isEqualTo("ETHUSDT");
            assertThat(bar.closed()).isTrue();
        }

        @Test
        @DisplayName("应忽略非 kline 消息")
        void shouldIgnoreNonKlineMessage() {
            String message = """
                    {"e":"24hrTicker","s":"BTCUSDT","c":"16750.00"}
                    """;

            client.handleMessage(message);

            assertThat(receivedEvent.get()).isNull();
        }

        @Test
        @DisplayName("应处理畸形 JSON 而不抛异常")
        void shouldHandleMalformedJsonGracefully() {
            client.handleMessage("not valid json");

            assertThat(receivedEvent.get()).isNull();
            assertThat(client.health().lastError()).isNotNull();
        }

        @Test
        @DisplayName("应更新消息计数和时间戳")
        void shouldUpdateMessageCountAndTimestamp() {
            String message = """
                    {
                        "e": "kline",
                        "E": 1672515782136,
                        "s": "BTCUSDT",
                        "k": {
                            "t": 1672515780000,
                            "T": 1672515839999,
                            "s": "BTCUSDT",
                            "i": "1m",
                            "o": "16721.50",
                            "h": "16722.00",
                            "l": "16721.00",
                            "c": "16721.50",
                            "v": "100.5",
                            "q": "1679231.25",
                            "x": false
                        }
                    }
                    """;

            client.handleMessage(message);

            assertThat(client.health().messagesReceived()).isEqualTo(1);
            assertThat(client.health().lastMessageTimestamp()).isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("事件处理器回调")
    class EventHandlerCallback {

        @Test
        @DisplayName("应通过 onEvent 回调通知所有注册的处理器")
        void shouldNotifyRegisteredHandlers() {
            AtomicReference<BarEvent> handlerEvent = new AtomicReference<>();
            client.onEvent(event -> {
                if (event instanceof BarEvent bar) {
                    handlerEvent.set(bar);
                }
            });

            String message = """
                    {
                        "e": "kline",
                        "E": 1672515782136,
                        "s": "BTCUSDT",
                        "k": {
                            "t": 1672515780000,
                            "T": 1672515839999,
                            "s": "BTCUSDT",
                            "i": "1m",
                            "o": "16721.50",
                            "h": "16722.00",
                            "l": "16721.00",
                            "c": "16721.50",
                            "v": "100.5",
                            "q": "1679231.25",
                            "x": false
                        }
                    }
                    """;

            client.handleMessage(message);

            assertThat(handlerEvent.get()).isNotNull();
            assertThat(handlerEvent.get().instrument().symbol()).isEqualTo("BTCUSDT");
        }
    }

    @Nested
    @DisplayName("连接状态")
    class ConnectionState {

        @Test
        @DisplayName("初始状态应为未连接")
        void shouldStartDisconnected() {
            assertThat(client.isConnected()).isFalse();
            assertThat(client.health().connected()).isFalse();
            assertThat(client.health().reconnectCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("disconnect 后应报告未连接状态")
        void shouldReportDisconnectedAfterDisconnect() {
            client.disconnect();

            assertThat(client.isConnected()).isFalse();
        }
    }
}
