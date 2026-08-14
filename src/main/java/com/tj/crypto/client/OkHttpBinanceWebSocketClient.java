package com.tj.crypto.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tj.crypto.common.domain.ChannelType;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.config.properties.ConnectorProperties;
import com.tj.crypto.config.properties.MarketUniverseProperties;
import com.tj.crypto.event.MarketEventBus;
import com.tj.crypto.marketdata.connector.ConnectorHealth;
import com.tj.crypto.marketdata.connector.MarketDataConnector;
import com.tj.crypto.marketdata.connector.SubscriptionRequest;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.MarketEvent;
import com.tj.crypto.marketdata.normalize.BinanceKlineNormalizer;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Binance spot and USD-M perpetual public K-line connector. */
@Slf4j
@Component
@ConditionalOnProperty(name = "crypto.connector.binance-enabled",
        havingValue = "true", matchIfMissing = true)
public class OkHttpBinanceWebSocketClient implements MarketDataConnector {

    private final BinanceKlineNormalizer normalizer;
    private final MarketEventBus eventBus;
    private final ConnectorProperties properties;
    private final MarketUniverseProperties marketUniverse;
    private final ObjectMapper objectMapper;
    private final Map<MarketType, Set<SubscriptionRequest>> subscriptions =
            new EnumMap<>(MarketType.class);
    private final Map<MarketType, BinanceMarketWebSocketSession> sessions =
            new EnumMap<>(MarketType.class);
    private final List<Consumer<MarketEvent>> handlers = new CopyOnWriteArrayList<>();
    private final AtomicLong requestId = new AtomicLong(1);
    private final AtomicLong parsedMessages = new AtomicLong();
    private final AtomicLong lastParsedTimestamp = new AtomicLong();
    private volatile String parseError;

    public OkHttpBinanceWebSocketClient(OkHttpClient httpClient,
                                         BinanceKlineNormalizer normalizer,
                                         MarketEventBus eventBus,
                                         ConnectorProperties properties,
                                         MarketUniverseProperties marketUniverse,
                                         ObjectMapper objectMapper) {
        this.normalizer = normalizer;
        this.eventBus = eventBus;
        this.properties = properties;
        this.marketUniverse = marketUniverse;
        this.objectMapper = objectMapper;
        subscriptions.put(MarketType.SPOT, new CopyOnWriteArraySet<>());
        subscriptions.put(MarketType.PERPETUAL, new CopyOnWriteArraySet<>());
        registerConfiguredSubscriptions();
        sessions.put(MarketType.SPOT, createSession(
                httpClient, MarketType.SPOT, properties.getBinanceSpotWsUrl()));
        sessions.put(MarketType.PERPETUAL, createSession(
                httpClient, MarketType.PERPETUAL, properties.getBinancePerpetualWsUrl()));
    }

    /** Test-friendly constructor. */
    OkHttpBinanceWebSocketClient(OkHttpClient httpClient,
                                 BinanceKlineNormalizer normalizer,
                                 MarketEventBus eventBus,
                                 List<String> symbols) {
        this(httpClient, normalizer, eventBus, testProperties(symbols),
                new MarketUniverseProperties(), new ObjectMapper());
    }

    @Override
    public void connect() {
        if (!properties.isBinanceEnabled()) return;
        sessions.values().forEach(BinanceMarketWebSocketSession::connect);
    }

    @Override
    public void disconnect() {
        sessions.values().forEach(BinanceMarketWebSocketSession::disconnect);
    }

    @Override
    public boolean isConnected() {
        return !sessions.isEmpty()
                && sessions.values().stream().allMatch(BinanceMarketWebSocketSession::isConnected);
    }

    @Override
    public void subscribe(SubscriptionRequest request) {
        validate(request);
        subscriptions.get(request.marketType()).add(request);
        sessions.get(request.marketType()).subscribe(request);
    }

    @Override
    public void unsubscribe(SubscriptionRequest request) {
        validate(request);
        subscriptions.get(request.marketType()).remove(request);
        sessions.get(request.marketType()).unsubscribe(request);
    }

    @Override
    public ConnectorHealth health() {
        List<ConnectorHealth> health = sessions.values().stream()
                .map(BinanceMarketWebSocketSession::health)
                .toList();
        long lastMessage = Math.max(lastParsedTimestamp.get(), health.stream()
                .mapToLong(ConnectorHealth::lastMessageTimestamp).max().orElse(0));
        long messages = Math.max(parsedMessages.get(), health.stream()
                .mapToLong(ConnectorHealth::messagesReceived).sum());
        long reconnects = health.stream().mapToLong(ConnectorHealth::reconnectCount).sum();
        String errors = health.stream().map(ConnectorHealth::lastError)
                .filter(error -> error != null && !error.isBlank())
                .collect(Collectors.joining("; "));
        if (parseError != null && !parseError.isBlank()) {
            errors = errors.isBlank() ? parseError : errors + "; " + parseError;
        }
        return new ConnectorHealth(isConnected(), lastMessage, messages, reconnects,
                errors.isBlank() ? null : errors);
    }

    @Override
    public void onEvent(Consumer<MarketEvent> handler) {
        handlers.add(handler);
    }

    String buildConnectionUrl() {
        return buildConnectionUrl(MarketType.PERPETUAL);
    }

    String buildConnectionUrl(MarketType marketType) {
        return marketType == MarketType.SPOT
                ? properties.getBinanceSpotWsUrl() : properties.getBinancePerpetualWsUrl();
    }

    String buildConfiguredKlineSubscribeMessage() {
        return buildConfiguredKlineSubscribeMessage(MarketType.PERPETUAL);
    }

    String buildConfiguredKlineSubscribeMessage(MarketType marketType) {
        return buildSubscriptionMessage("SUBSCRIBE", subscriptions.get(marketType));
    }

    String buildSubscriptionMessage(String operation,
                                    Collection<SubscriptionRequest> requests) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("method", operation);
        ArrayNode params = root.putArray("params");
        requests.forEach(request -> params.add(streamName(request)));
        root.put("id", requestId.getAndIncrement());
        return root.toString();
    }

    void handleMessage(String text) {
        handleMessage(MarketType.PERPETUAL, text);
    }

    void handleMessage(MarketType marketType, String text) {
        parsedMessages.incrementAndGet();
        lastParsedTimestamp.set(System.currentTimeMillis());
        try {
            JsonNode root = objectMapper.readTree(text);
            JsonNode payload = root.has("data") && root.path("data").isObject()
                    ? root.path("data") : root;
            if (!"kline".equals(payload.path("e").asText())) return;
            long eventTime = payload.path("E").asLong(System.currentTimeMillis());
            BarEvent bar = normalizer.normalize(payload.path("k"), eventTime, marketType);
            if (bar == null) return;
            eventBus.publish(bar);
            for (Consumer<MarketEvent> handler : handlers) {
                try {
                    handler.accept(bar);
                } catch (RuntimeException e) {
                    log.warn("Binance event handler failed: {}", e.getMessage());
                }
            }
            parseError = null;
        } catch (Exception e) {
            parseError = e.getMessage();
            log.warn("Cannot process Binance {} message: {}", marketType, e.getMessage());
        }
    }

    private BinanceMarketWebSocketSession createSession(OkHttpClient httpClient,
                                                          MarketType marketType,
                                                          String websocketUrl) {
        return new BinanceMarketWebSocketSession(
                httpClient,
                marketType,
                websocketUrl,
                () -> List.copyOf(subscriptions.get(marketType)),
                this::buildSubscriptionMessage,
                text -> handleMessage(marketType, text),
                properties.getMaxReconnectAttempts());
    }

    private void registerConfiguredSubscriptions() {
        for (String symbol : properties.getSymbols()) {
            for (String timeframe : properties.getBinanceTimeframes()) {
                addConfigured(MarketType.SPOT, symbol, timeframe);
                addConfigured(MarketType.PERPETUAL, symbol, timeframe);
            }
        }
    }

    private void addConfigured(MarketType marketType, String symbol, String timeframe) {
        marketUniverse.validate(Exchange.BINANCE, marketType, symbol);
        subscriptions.get(marketType).add(new SubscriptionRequest(
                Exchange.BINANCE, marketType, ChannelType.KLINE,
                MarketUniverseProperties.normalizeSymbol(symbol), Timeframe.fromCode(timeframe)));
    }

    private void validate(SubscriptionRequest request) {
        if (request.exchange() != Exchange.BINANCE
                || request.channelType() != ChannelType.KLINE
                || !sessions.containsKey(request.marketType())
                || request.timeframe() == null) {
            throw new IllegalArgumentException(
                    "Binance connector supports SPOT/PERPETUAL KLINE subscriptions only");
        }
        marketUniverse.validate(request.exchange(), request.marketType(), request.symbol());
    }

    private String streamName(SubscriptionRequest request) {
        return request.symbol().toLowerCase(java.util.Locale.ROOT)
                + "@kline_" + request.timeframe().getCode();
    }

    private static ConnectorProperties testProperties(List<String> symbols) {
        ConnectorProperties properties = new ConnectorProperties();
        properties.setSymbols(new ArrayList<>(symbols));
        properties.setBinanceTimeframes(List.of("1m"));
        return properties;
    }
}
