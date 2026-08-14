package com.tj.crypto.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tj.crypto.common.domain.ChannelType;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.config.properties.OkxProperties;
import com.tj.crypto.config.properties.MarketUniverseProperties;
import com.tj.crypto.event.MarketEventBus;
import com.tj.crypto.marketdata.connector.ConnectorHealth;
import com.tj.crypto.marketdata.connector.MarketDataConnector;
import com.tj.crypto.marketdata.connector.SubscriptionRequest;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.MarketEvent;
import com.tj.crypto.marketdata.normalize.OkxKlineNormalizer;
import com.tj.crypto.marketdata.okx.OkxMarketDataMappings;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** OKX public business WebSocket connector for candle channels. */
@Slf4j
@Component
@ConditionalOnProperty(name = "crypto.connector.okx.enabled", havingValue = "true")
public class OkHttpOkxWebSocketClient implements MarketDataConnector {

    private static final long MAX_RECONNECT_DELAY_SECONDS = 60;

    private final OkHttpClient httpClient;
    private final OkxProperties properties;
    private final OkxKlineNormalizer normalizer;
    private final MarketEventBus eventBus;
    private final ObjectMapper objectMapper;
    private final MarketUniverseProperties marketUniverse;
    private final Set<SubscriptionRequest> subscriptions = new CopyOnWriteArraySet<>();
    private final List<Consumer<MarketEvent>> handlers = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "okx-marketdata-scheduler");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean connected = new AtomicBoolean();
    private final AtomicBoolean connecting = new AtomicBoolean();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final AtomicLong messagesReceived = new AtomicLong();
    private final AtomicLong reconnectCount = new AtomicLong();
    private final AtomicLong consecutiveReconnectAttempts = new AtomicLong();
    private final AtomicLong lastMessageTimestamp = new AtomicLong();
    private final AtomicReference<String> lastError = new AtomicReference<>();

    private volatile boolean manualDisconnect;
    private volatile WebSocket webSocket;
    private volatile ScheduledFuture<?> pingTask;
    private volatile ScheduledFuture<?> reconnectTask;

    public OkHttpOkxWebSocketClient(OkHttpClient httpClient, OkxProperties properties,
                                    OkxKlineNormalizer normalizer, MarketEventBus eventBus,
                                    ObjectMapper objectMapper,
                                    MarketUniverseProperties marketUniverse) {
        this.httpClient = httpClient;
        this.properties = properties;
        this.normalizer = normalizer;
        this.eventBus = eventBus;
        this.objectMapper = objectMapper;
        this.marketUniverse = marketUniverse;
        registerConfiguredSubscriptions();
    }

    @Override
    public void connect() {
        if (connected.get() || !connecting.compareAndSet(false, true)) return;
        manualDisconnect = false;
        Request request = new Request.Builder().url(properties.getWebsocketUrl()).build();
        log.info("Connecting to OKX market data WebSocket: {}", properties.getWebsocketUrl());
        try {
            webSocket = httpClient.newWebSocket(request, new OkxListener());
        } catch (RuntimeException e) {
            connecting.set(false);
            lastError.set(e.getMessage());
            throw e;
        }
    }

    @Override
    public void disconnect() {
        manualDisconnect = true;
        connected.set(false);
        connecting.set(false);
        cancelPing();
        cancelReconnect();
        WebSocket socket = webSocket;
        webSocket = null;
        if (socket != null) socket.close(1000, "Client disconnect");
    }

    @PreDestroy
    public void shutdown() {
        disconnect();
        scheduler.shutdownNow();
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public void subscribe(SubscriptionRequest request) {
        validate(request);
        subscriptions.add(request);
        send(buildSubscriptionMessage("subscribe", List.of(request)));
    }

    @Override
    public void unsubscribe(SubscriptionRequest request) {
        validate(request);
        subscriptions.remove(request);
        send(buildSubscriptionMessage("unsubscribe", List.of(request)));
    }

    @Override
    public ConnectorHealth health() {
        return new ConnectorHealth(connected.get(), lastMessageTimestamp.get(),
                messagesReceived.get(), reconnectCount.get(), lastError.get());
    }

    @Override
    public void onEvent(Consumer<MarketEvent> handler) {
        handlers.add(handler);
    }

    String buildSubscriptionMessage(String operation, Collection<SubscriptionRequest> requests) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("op", operation);
        ArrayNode args = root.putArray("args");
        for (SubscriptionRequest request : requests) {
            ObjectNode arg = args.addObject();
            arg.put("channel", OkxMarketDataMappings.websocketChannel(request.timeframe()));
            arg.put("instId", OkxMarketDataMappings.toOkxInstrumentId(
                    request.symbol(), request.marketType()));
        }
        return root.toString();
    }

    void handleMessage(String text) {
        if ("pong".equals(text)) return;
        messagesReceived.incrementAndGet();
        lastMessageTimestamp.set(System.currentTimeMillis());
        try {
            JsonNode root = objectMapper.readTree(text);
            if (root.has("event")) {
                if ("error".equals(root.path("event").asText())) {
                    lastError.set(root.path("msg").asText("OKX subscription error"));
                    log.warn("OKX subscription error: {}", text);
                }
                return;
            }
            JsonNode arg = root.path("arg");
            JsonNode data = root.path("data");
            String channel = arg.path("channel").asText();
            String instrumentId = arg.path("instId").asText();
            if (!channel.startsWith("candle") || instrumentId.isBlank() || !data.isArray()) return;

            long receivedAt = System.currentTimeMillis();
            for (JsonNode candle : data) {
                BarEvent bar = normalizer.normalize(instrumentId, channel, candle, receivedAt);
                if (bar == null) continue;
                eventBus.publish(bar);
                for (Consumer<MarketEvent> handler : handlers) {
                    try {
                        handler.accept(bar);
                    } catch (RuntimeException e) {
                        log.warn("OKX event handler failed: {}", e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            lastError.set(e.getMessage());
            log.warn("Cannot process OKX message: {}", e.getMessage());
        }
    }

    private void registerConfiguredSubscriptions() {
        for (String instrumentId : properties.getInstruments()) {
            for (String timeframeCode : properties.getTimeframes()) {
                SubscriptionRequest request = new SubscriptionRequest(
                        Exchange.OKX,
                        OkxMarketDataMappings.marketType(instrumentId),
                        ChannelType.KLINE,
                        OkxMarketDataMappings.toInternalSymbol(instrumentId),
                        Timeframe.fromCode(timeframeCode));
                validate(request);
                subscriptions.add(request);
            }
        }
    }

    private void validate(SubscriptionRequest request) {
        if (request.exchange() != Exchange.OKX || request.channelType() != ChannelType.KLINE
                || request.timeframe() == null) {
            throw new IllegalArgumentException("OKX connector currently supports OKX KLINE subscriptions only");
        }
        marketUniverse.validate(request.exchange(), request.marketType(), request.symbol());
    }

    private void send(String message) {
        WebSocket socket = webSocket;
        if (socket != null && connected.get()) socket.send(message);
    }

    private void subscribeAll() {
        if (!subscriptions.isEmpty()) send(buildSubscriptionMessage("subscribe", subscriptions));
    }

    private void startPing() {
        cancelPing();
        pingTask = scheduler.scheduleAtFixedRate(() -> {
            WebSocket socket = webSocket;
            if (socket != null && connected.get()) socket.send("ping");
        }, 20, 20, TimeUnit.SECONDS);
    }

    private void cancelPing() {
        ScheduledFuture<?> task = pingTask;
        if (task != null) task.cancel(true);
        pingTask = null;
    }

    private void scheduleReconnect() {
        if (manualDisconnect || !reconnectScheduled.compareAndSet(false, true)) return;
        reconnectCount.incrementAndGet();
        long attempt = consecutiveReconnectAttempts.incrementAndGet();
        long delay = Math.min(1L << Math.min(attempt - 1, 6), MAX_RECONNECT_DELAY_SECONDS);
        reconnectTask = scheduler.schedule(() -> {
            reconnectScheduled.set(false);
            reconnectTask = null;
            if (!manualDisconnect) connect();
        }, delay, TimeUnit.SECONDS);
    }

    private void cancelReconnect() {
        ScheduledFuture<?> task = reconnectTask;
        if (task != null) task.cancel(true);
        reconnectTask = null;
        reconnectScheduled.set(false);
        consecutiveReconnectAttempts.set(0);
    }

    private class OkxListener extends WebSocketListener {
        @Override
        public void onOpen(@NotNull WebSocket socket, @NotNull Response response) {
            if (manualDisconnect) {
                connecting.set(false);
                socket.close(1000, "Client already disconnected");
                return;
            }
            webSocket = socket;
            connecting.set(false);
            connected.set(true);
            consecutiveReconnectAttempts.set(0);
            lastError.set(null);
            subscribeAll();
            startPing();
            log.info("OKX market data WebSocket connected with {} subscriptions", subscriptions.size());
        }

        @Override
        public void onMessage(@NotNull WebSocket socket, @NotNull String text) {
            handleMessage(text);
        }

        @Override
        public void onMessage(@NotNull WebSocket socket, @NotNull ByteString bytes) {
            handleMessage(bytes.utf8());
        }

        @Override
        public void onClosed(@NotNull WebSocket socket, int code, @NotNull String reason) {
            connected.set(false);
            connecting.set(false);
            cancelPing();
            scheduleReconnect();
        }

        @Override
        public void onFailure(@NotNull WebSocket socket, @NotNull Throwable error, Response response) {
            connected.set(false);
            connecting.set(false);
            cancelPing();
            lastError.set(error.getMessage());
            log.warn("OKX market data WebSocket failure: {}", error.getMessage());
            scheduleReconnect();
        }
    }
}
