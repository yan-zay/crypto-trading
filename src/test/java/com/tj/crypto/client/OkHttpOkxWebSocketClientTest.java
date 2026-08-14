package com.tj.crypto.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.common.domain.ChannelType;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.config.properties.OkxProperties;
import com.tj.crypto.config.properties.MarketUniverseProperties;
import com.tj.crypto.event.InMemoryEventBus;
import com.tj.crypto.marketdata.connector.SubscriptionRequest;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.normalize.OkxKlineNormalizer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OkHttpOkxWebSocketClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<BarEvent> received = new AtomicReference<>();
    private OkHttpOkxWebSocketClient client;

    @BeforeEach
    void setUp() {
        OkxProperties properties = new OkxProperties();
        properties.setInstruments(List.of("BTC-USDT-SWAP"));
        properties.setTimeframes(List.of("1m"));
        InMemoryEventBus eventBus = new InMemoryEventBus();
        eventBus.subscribe(BarEvent.class, received::set);
        client = new OkHttpOkxWebSocketClient(
                new OkHttpClient(), properties, new OkxKlineNormalizer(), eventBus, mapper,
                new MarketUniverseProperties());
    }

    @Test
    void buildsOfficialBusinessChannelSubscription() throws Exception {
        SubscriptionRequest request = new SubscriptionRequest(
                Exchange.OKX, MarketType.PERPETUAL, ChannelType.KLINE, "BTCUSDT", Timeframe.H1);

        JsonNode json = mapper.readTree(client.buildSubscriptionMessage("subscribe", List.of(request)));

        assertThat(json.path("op").asText()).isEqualTo("subscribe");
        assertThat(json.path("args").get(0).path("channel").asText()).isEqualTo("candle1H");
        assertThat(json.path("args").get(0).path("instId").asText()).isEqualTo("BTC-USDT-SWAP");
    }

    @Test
    void publishesEveryCandleInPushPayload() {
        client.handleMessage("""
                {"arg":{"channel":"candle1m","instId":"BTC-USDT-SWAP"},"data":[
                  ["1700000000000","100","110","90","105","2","200","210","1"]
                ]}
                """);

        assertThat(received.get()).isNotNull();
        assertThat(received.get().instrument().exchange()).isEqualTo(Exchange.OKX);
        assertThat(received.get().instrument().marketType()).isEqualTo(MarketType.PERPETUAL);
        assertThat(received.get().close()).isEqualByComparingTo("105");
        assertThat(received.get().volume()).isEqualByComparingTo("200");
        assertThat(client.health().messagesReceived()).isEqualTo(1);
    }

    @Test
    void buildsSpotSubscription() throws Exception {
        SubscriptionRequest request = new SubscriptionRequest(
                Exchange.OKX, MarketType.SPOT, ChannelType.KLINE, "ETHUSDT", Timeframe.M5);

        JsonNode json = mapper.readTree(
                client.buildSubscriptionMessage("subscribe", List.of(request)));

        assertThat(json.path("args").get(0).path("instId").asText()).isEqualTo("ETH-USDT");
        assertThat(json.path("args").get(0).path("channel").asText()).isEqualTo("candle5m");
    }

    @Test
    void recordsProtocolErrorsWithoutPublishing() {
        client.handleMessage("{\"event\":\"error\",\"code\":\"60012\",\"msg\":\"Invalid request\"}");

        assertThat(received.get()).isNull();
        assertThat(client.health().lastError()).isEqualTo("Invalid request");
    }

    @Test
    void rejectsDatedFuturesInsteadOfMisclassifyingThemAsSpot() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                com.tj.crypto.marketdata.okx.OkxMarketDataMappings.marketType(
                        "BTC-USDT-260925")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dated futures");
    }

    @Test
    void rejectsConfiguredInstrumentOutsideMarketUniverse() {
        OkxProperties properties = new OkxProperties();
        properties.setInstruments(List.of("SOL-USDT-SWAP"));

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                new OkHttpOkxWebSocketClient(
                        new OkHttpClient(), properties, new OkxKlineNormalizer(),
                        new InMemoryEventBus(), mapper, new MarketUniverseProperties())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported symbol");
    }
}
