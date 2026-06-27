package com.tj.crypto.client;

import com.tj.crypto.config.properties.CoinglassProperties;
import com.tj.crypto.event.InMemoryEventBus;
import com.tj.crypto.marketdata.model.LiquidationEvent;
import com.tj.crypto.marketdata.model.MarketEvent;
import com.tj.crypto.marketdata.normalize.CoinglassLiquidationNormalizer;
import com.tj.crypto.storage.mapper.RawMessageMapper;
import com.tj.crypto.storage.service.RawMessagePersistenceService;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OkHttpCoinglassWebSocketClient 单元测试。
 * 测试 JSON 解析和事件发布逻辑，不依赖真实网络连接。
 */
class OkHttpCoinglassWebSocketClientTest {

    private OkHttpCoinglassWebSocketClient client;
    private InMemoryEventBus eventBus;
    private final AtomicReference<MarketEvent> receivedEvent = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        eventBus = new InMemoryEventBus();
        CoinglassLiquidationNormalizer normalizer = new CoinglassLiquidationNormalizer();
        OkHttpClient okHttpClient = new OkHttpClient();
        CoinglassProperties properties = new CoinglassProperties();
        properties.setApiKey("test-key");

        // 使用 null mapper 构造 service — saveRawMessage 会抛异常，但 handleMessage 会 catch 住
        RawMessagePersistenceService rawMessageService = new RawMessagePersistenceService(null);

        client = new OkHttpCoinglassWebSocketClient(okHttpClient, properties, normalizer, eventBus, rawMessageService);

        eventBus.subscribe(LiquidationEvent.class, receivedEvent::set);
    }

    @Nested
    @DisplayName("liquidation 消息处理")
    class LiquidationMessageHandling {

        @Test
        @DisplayName("应正确解析爆仓 JSON 并发布 LiquidationEvent")
        void shouldParseLiquidationAndPublishEvent() {
            String message = """
                    {
                        "channel": "liquidationOrders",
                        "data": [
                            {
                                "baseAsset": "BTC",
                                "exName": "Binance",
                                "price": 16721.50,
                                "side": 1,
                                "symbol": "BTCUSDT",
                                "time": 1672515782000,
                                "volUsd": 50000.00
                            }
                        ],
                        "time": 1672515782136
                    }
                    """;

            client.handleMessage(message);

            assertThat(receivedEvent.get()).isNotNull();
            assertThat(receivedEvent.get()).isInstanceOf(LiquidationEvent.class);
            LiquidationEvent event = (LiquidationEvent) receivedEvent.get();
            assertThat(event.instrument().symbol()).isEqualTo("BTCUSDT");
            assertThat(event.price()).isEqualByComparingTo(new BigDecimal("16721.50"));
            assertThat(event.quantityUsd()).isEqualByComparingTo(new BigDecimal("50000.00"));
            assertThat(event.exchangeName()).isEqualTo("Binance");
        }

        @Test
        @DisplayName("应正确解析空方向爆仓")
        void shouldParseShortLiquidation() {
            String message = """
                    {
                        "channel": "liquidationOrders",
                        "data": [
                            {
                                "baseAsset": "ETH",
                                "exName": "OKX",
                                "price": 1200.00,
                                "side": 2,
                                "symbol": "ETHUSDT",
                                "time": 1672515782000,
                                "volUsd": 30000.00
                            }
                        ],
                        "time": 1672515782136
                    }
                    """;

            client.handleMessage(message);

            assertThat(receivedEvent.get()).isNotNull();
            LiquidationEvent event = (LiquidationEvent) receivedEvent.get();
            assertThat(event.instrument().symbol()).isEqualTo("ETHUSDT");
            assertThat(event.side().getCode()).isEqualTo(2);
        }

        @Test
        @DisplayName("应处理 data 为空数组的消息")
        void shouldHandleEmptyDataArray() {
            String message = """
                    {
                        "channel": "liquidationOrders",
                        "data": [],
                        "time": 1672515782136
                    }
                    """;

            client.handleMessage(message);

            assertThat(receivedEvent.get()).isNull();
            // 不应报错
            assertThat(client.health().lastError()).isNull();
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
                        "channel": "liquidationOrders",
                        "data": [
                            {
                                "baseAsset": "BTC",
                                "exName": "Binance",
                                "price": 16721.50,
                                "side": 1,
                                "symbol": "BTCUSDT",
                                "time": 1672515782000,
                                "volUsd": 50000.00
                            }
                        ],
                        "time": 1672515782136
                    }
                    """;

            client.handleMessage(message);

            assertThat(client.health().messagesReceived()).isEqualTo(1);
            assertThat(client.health().lastMessageTimestamp()).isGreaterThan(0);
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
        }

        @Test
        @DisplayName("disconnect 后应报告未连接状态")
        void shouldReportDisconnectedAfterDisconnect() {
            client.disconnect();

            assertThat(client.isConnected()).isFalse();
        }
    }
}
