package com.tj.crypto.client;

import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.marketdata.connector.ConnectorHealth;
import com.tj.crypto.marketdata.connector.SubscriptionRequest;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One independently reconnecting Binance public-market WebSocket session. */
@Slf4j
final class BinanceMarketWebSocketSession {

    private static final long MAX_RECONNECT_DELAY_SECONDS = 60;

    private final OkHttpClient httpClient;
    private final MarketType marketType;
    private final String websocketUrl;
    private final Supplier<Collection<SubscriptionRequest>> subscriptions;
    private final BiFunction<String, Collection<SubscriptionRequest>, String> messageFactory;
    private final Consumer<String> messageConsumer;
    private final int maxReconnectAttempts;
    private final ScheduledExecutorService scheduler;
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
    private volatile ScheduledFuture<?> reconnectTask;

    BinanceMarketWebSocketSession(OkHttpClient httpClient,
                                  MarketType marketType,
                                  String websocketUrl,
                                  Supplier<Collection<SubscriptionRequest>> subscriptions,
                                  BiFunction<String, Collection<SubscriptionRequest>, String> messageFactory,
                                  Consumer<String> messageConsumer,
                                  int maxReconnectAttempts) {
        this.httpClient = httpClient;
        this.marketType = marketType;
        this.websocketUrl = websocketUrl;
        this.subscriptions = subscriptions;
        this.messageFactory = messageFactory;
        this.messageConsumer = messageConsumer;
        this.maxReconnectAttempts = maxReconnectAttempts;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "binance-" + marketType.getCode() + "-scheduler");
            thread.setDaemon(true);
            return thread;
        });
    }

    void connect() {
        if (connected.get() || !connecting.compareAndSet(false, true)) return;
        manualDisconnect = false;
        try {
            Request request = new Request.Builder().url(websocketUrl).build();
            webSocket = httpClient.newWebSocket(request, new Listener());
            log.info("Connecting to Binance {} WebSocket: {}", marketType, websocketUrl);
        } catch (RuntimeException e) {
            connecting.set(false);
            lastError.set(e.getMessage());
            scheduleReconnect();
        }
    }

    void disconnect() {
        manualDisconnect = true;
        connected.set(false);
        connecting.set(false);
        cancelReconnect();
        WebSocket socket = webSocket;
        webSocket = null;
        if (socket != null) socket.close(1000, "Client disconnect");
        scheduler.shutdownNow();
    }

    void subscribe(SubscriptionRequest request) {
        send(messageFactory.apply("SUBSCRIBE", java.util.List.of(request)));
    }

    void unsubscribe(SubscriptionRequest request) {
        send(messageFactory.apply("UNSUBSCRIBE", java.util.List.of(request)));
    }

    boolean isConnected() {
        return connected.get();
    }

    ConnectorHealth health() {
        return new ConnectorHealth(connected.get(), lastMessageTimestamp.get(),
                messagesReceived.get(), reconnectCount.get(), lastError.get());
    }

    private void subscribeAll() {
        Collection<SubscriptionRequest> configured = subscriptions.get();
        if (!configured.isEmpty()) send(messageFactory.apply("SUBSCRIBE", configured));
    }

    private void send(String message) {
        WebSocket socket = webSocket;
        if (socket != null && connected.get()) socket.send(message);
    }

    private void scheduleReconnect() {
        if (manualDisconnect || scheduler.isShutdown()
                || !reconnectScheduled.compareAndSet(false, true)) return;
        long attempt = consecutiveReconnectAttempts.incrementAndGet();
        if (maxReconnectAttempts > 0 && attempt > maxReconnectAttempts) {
            reconnectScheduled.set(false);
            lastError.set("Maximum reconnect attempts reached");
            return;
        }
        reconnectCount.incrementAndGet();
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

    private final class Listener extends WebSocketListener {
        @Override
        public void onOpen(@NotNull WebSocket socket, @NotNull Response response) {
            if (manualDisconnect || webSocket != socket) {
                connecting.set(false);
                socket.close(1000, "Stale connection");
                return;
            }
            connecting.set(false);
            connected.set(true);
            consecutiveReconnectAttempts.set(0);
            lastError.set(null);
            subscribeAll();
            log.info("Binance {} WebSocket connected", marketType);
        }

        @Override
        public void onMessage(@NotNull WebSocket socket, @NotNull String text) {
            if (webSocket != socket) return;
            messagesReceived.incrementAndGet();
            lastMessageTimestamp.set(System.currentTimeMillis());
            messageConsumer.accept(text);
        }

        @Override
        public void onMessage(@NotNull WebSocket socket, @NotNull ByteString bytes) {
            onMessage(socket, bytes.utf8());
        }

        @Override
        public void onClosed(@NotNull WebSocket socket, int code, @NotNull String reason) {
            if (webSocket != socket) return;
            connected.set(false);
            connecting.set(false);
            scheduleReconnect();
        }

        @Override
        public void onFailure(@NotNull WebSocket socket, @NotNull Throwable error, Response response) {
            if (webSocket != socket) return;
            connected.set(false);
            connecting.set(false);
            lastError.set(error.getMessage());
            log.warn("Binance {} WebSocket failure: {}", marketType, error.getMessage());
            scheduleReconnect();
        }
    }
}
