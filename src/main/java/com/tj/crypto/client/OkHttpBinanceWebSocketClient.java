package com.tj.crypto.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.event.MarketEventBus;
import com.tj.crypto.marketdata.connector.ConnectorHealth;
import com.tj.crypto.marketdata.connector.MarketDataConnector;
import com.tj.crypto.marketdata.connector.SubscriptionRequest;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.MarketEvent;
import com.tj.crypto.marketdata.normalize.BinanceKlineNormalizer;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Binance WebSocket 客户端（OkHttp 实现）。
 * 连接 Binance 期货 WebSocket，接收 kline 数据并标准化为 BarEvent。
 *
 * <p>通过 {@code crypto.websocket.client-type=okhttp} 启用。</p>
 *
 * @Author zay
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "crypto.websocket.client-type", havingValue = "okhttp")
public class OkHttpBinanceWebSocketClient implements MarketDataConnector {

    private static final String WS_URL_TEMPLATE = "wss://fstream.binance.com/ws/%s";
    private static final long INITIAL_RECONNECT_DELAY_MS = 1_000;
    private static final long MAX_RECONNECT_DELAY_MS = 60_000;
    private static final double RECONNECT_BACKOFF_MULTIPLIER = 2.0;
    private static final long PING_INTERVAL_MS = 3 * 60 * 1000; // 3 分钟

    private final OkHttpClient okHttpClient;
    private final BinanceKlineNormalizer klineNormalizer;
    private final MarketEventBus eventBus;
    private final List<String> symbols;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicLong messagesReceived = new AtomicLong(0);
    private final AtomicLong reconnectCount = new AtomicLong(0);
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private final AtomicLong lastMessageTimestamp = new AtomicLong(0);
    private final List<Consumer<MarketEvent>> eventHandlers = new CopyOnWriteArrayList<>();

    private volatile WebSocket webSocket;
    private volatile boolean manualDisconnect = false;
    private volatile Thread reconnectThread;
    private volatile Thread pingThread;

    public OkHttpBinanceWebSocketClient(OkHttpClient okHttpClient,
                                         BinanceKlineNormalizer klineNormalizer,
                                         MarketEventBus eventBus,
                                         @Value("${crypto.binance.symbols:BTCUSDT,ETHUSDT}") List<String> symbols) {
        this.okHttpClient = okHttpClient;
        this.klineNormalizer = klineNormalizer;
        this.eventBus = eventBus;
        this.symbols = symbols;
    }

    @Override
    public void connect() {
        if (connected.get()) {
            log.warn("Binance WebSocket already connected, skipping");
            return;
        }
        manualDisconnect = false;

        String streams = symbols.stream()
                .map(s -> s.toLowerCase() + "@kline_1m")
                .collect(Collectors.joining("/"));
        String url = String.format(WS_URL_TEMPLATE, streams);

        log.info("Connecting to Binance WebSocket: {}", url);
        Request request = new Request.Builder().url(url).build();

        okHttpClient.newWebSocket(request, new BinanceWebSocketListener());
    }

    @Override
    public void disconnect() {
        manualDisconnect = true;
        stopPingThread();
        if (webSocket != null) {
            webSocket.close(1000, "Client disconnect");
            webSocket = null;
        }
        connected.set(false);
        log.info("Binance WebSocket disconnected");
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public void subscribe(SubscriptionRequest request) {
        if (!isConnected() || webSocket == null) {
            log.warn("Cannot subscribe, not connected");
            return;
        }
        String stream = request.symbol().toLowerCase() + "@kline_" + request.timeframe().getCode();
        String message = """
                {"method":"SUBSCRIBE","params":["%s"],"id":1}
                """.formatted(stream);
        webSocket.send(message);
        log.info("Subscribed to stream: {}", stream);
    }

    @Override
    public void unsubscribe(SubscriptionRequest request) {
        if (!isConnected() || webSocket == null) {
            return;
        }
        String stream = request.symbol().toLowerCase() + "@kline_" + request.timeframe().getCode();
        String message = """
                {"method":"UNSUBSCRIBE","params":["%s"],"id":2}
                """.formatted(stream);
        webSocket.send(message);
        log.info("Unsubscribed from stream: {}", stream);
    }

    @Override
    public ConnectorHealth health() {
        return new ConnectorHealth(
                connected.get(),
                lastMessageTimestamp.get(),
                messagesReceived.get(),
                reconnectCount.get(),
                lastError.get()
        );
    }

    @Override
    public void onEvent(Consumer<MarketEvent> handler) {
        eventHandlers.add(handler);
    }

    /**
     * 发送订阅消息（供 Service 层调用）。
     */
    public void sendSubscribeMessage(String message) {
        if (isConnected() && webSocket != null) {
            webSocket.send(message);
            log.debug("Sent subscribe message: {}", message);
        } else {
            log.warn("Cannot send message, not connected");
        }
    }

    /**
     * 处理接收到的 WebSocket 消息。
     * 包级访问，便于单元测试。
     */
    void handleMessage(String text) {
        try {
            messagesReceived.incrementAndGet();
            lastMessageTimestamp.set(System.currentTimeMillis());

            JsonNode jsonNode = objectMapper.readTree(text);

            // Binance kline stream
            if (jsonNode.has("e") && "kline".equals(jsonNode.get("e").asText())) {
                handleKlineData(jsonNode);
            }
        } catch (Exception e) {
            log.error("Error processing Binance message: {}", e.getMessage(), e);
            lastError.set(e.getMessage());
        }
    }

    private void handleKlineData(JsonNode jsonNode) {
        try {
            JsonNode kline = jsonNode.get("k");
            long eventTime = jsonNode.has("E") ? jsonNode.get("E").asLong() : System.currentTimeMillis();

            BarEvent barEvent = klineNormalizer.normalize(kline, eventTime);
            if (barEvent != null) {
                eventBus.publish(barEvent);
                for (Consumer<MarketEvent> handler : eventHandlers) {
                    try {
                        handler.accept(barEvent);
                    } catch (Exception e) {
                        log.error("Event handler error: {}", e.getMessage(), e);
                    }
                }

                log.debug("BarEvent published: {} {} O={} H={} L={} C={} V={} closed={}",
                        barEvent.instrument().symbol(),
                        barEvent.timeframe().getCode(),
                        barEvent.open(), barEvent.high(), barEvent.low(), barEvent.close(),
                        barEvent.volume(), barEvent.closed());
            }
        } catch (Exception e) {
            log.error("Error parsing kline data: {}", e.getMessage(), e);
            lastError.set(e.getMessage());
        }
    }

    private void scheduleReconnect() {
        if (manualDisconnect) {
            return;
        }
        connected.set(false);
        stopPingThread();

        // 避免重复调度
        Thread existing = reconnectThread;
        if (existing != null && existing.isAlive()) {
            return;
        }

        reconnectThread = new Thread(() -> {
            long delay = INITIAL_RECONNECT_DELAY_MS;
            while (!manualDisconnect && !connected.get()) {
                try {
                    log.info("Reconnecting to Binance in {} ms...", delay);
                    Thread.sleep(delay);
                    reconnectCount.incrementAndGet();
                    connect();
                    // 等待连接结果
                    Thread.sleep(3000);
                    if (connected.get()) {
                        break;
                    }
                    delay = Math.min((long) (delay * RECONNECT_BACKOFF_MULTIPLIER), MAX_RECONNECT_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Reconnect thread interrupted");
                    break;
                }
            }
        }, "binance-reconnect");
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    private void startPingThread() {
        stopPingThread();
        pingThread = new Thread(() -> {
            while (connected.get() && !manualDisconnect) {
                try {
                    Thread.sleep(PING_INTERVAL_MS);
                    if (webSocket != null && connected.get()) {
                        webSocket.send("ping");
                        log.debug("Sent ping to Binance");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "binance-ping");
        pingThread.setDaemon(true);
        pingThread.start();
    }

    private void stopPingThread() {
        Thread t = pingThread;
        if (t != null) {
            t.interrupt();
            pingThread = null;
        }
    }

    /**
     * OkHttp WebSocket 监听器。
     */
    private class BinanceWebSocketListener extends WebSocketListener {

        @Override
        public void onOpen(@NotNull WebSocket ws, @NotNull Response response) {
            webSocket = ws;
            connected.set(true);
            lastError.set(null);
            log.info("Binance WebSocket connected");
            startPingThread();
        }

        @Override
        public void onMessage(@NotNull WebSocket ws, @NotNull String text) {
            if ("pong".equals(text)) {
                log.debug("Received pong from Binance");
                return;
            }
            handleMessage(text);
        }

        @Override
        public void onMessage(@NotNull WebSocket ws, @NotNull ByteString bytes) {
            // Binance 文本协议，一般不走二进制
            handleMessage(bytes.utf8());
        }

        @Override
        public void onClosing(@NotNull WebSocket ws, int code, @NotNull String reason) {
            log.info("Binance WebSocket closing: {} {}", code, reason);
            ws.close(code, reason);
            connected.set(false);
            scheduleReconnect();
        }

        @Override
        public void onFailure(@NotNull WebSocket ws, @NotNull Throwable t, Response response) {
            log.error("Binance WebSocket failure: {}", t.getMessage(), t);
            lastError.set(t.getMessage());
            connected.set(false);
            scheduleReconnect();
        }
    }
}
