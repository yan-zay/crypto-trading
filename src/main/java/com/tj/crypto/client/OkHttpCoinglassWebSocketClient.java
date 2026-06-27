package com.tj.crypto.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.common.domain.ChannelType;
import com.tj.crypto.config.properties.CoinglassProperties;
import com.tj.crypto.event.MarketEventBus;
import com.tj.crypto.marketdata.connector.ConnectorHealth;
import com.tj.crypto.marketdata.connector.MarketDataConnector;
import com.tj.crypto.marketdata.connector.SubscriptionRequest;
import com.tj.crypto.marketdata.model.LiquidationEvent;
import com.tj.crypto.marketdata.model.MarketEvent;
import com.tj.crypto.marketdata.normalize.CoinglassLiquidationNormalizer;
import com.tj.crypto.pojo.dto.CgResultDTO;
import com.tj.crypto.pojo.dto.LiquidationOrder;
import com.tj.crypto.storage.service.RawMessagePersistenceService;
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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Coinglass WebSocket 客户端（OkHttp 实现）。
 * 连接 Coinglass 爆仓数据流，接收并标准化为 LiquidationEvent。
 *
 * <p>通过 {@code crypto.websocket.client-type=okhttp} 启用。</p>
 *
 * @Author zay
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "crypto.websocket.client-type", havingValue = "okhttp")
public class OkHttpCoinglassWebSocketClient implements MarketDataConnector {

    private static final String WS_URL_TEMPLATE = "wss://open-ws.coinglass.com/ws-api?cg-api-key=%s";
    private static final long INITIAL_RECONNECT_DELAY_MS = 1_000;
    private static final long MAX_RECONNECT_DELAY_MS = 60_000;
    private static final double RECONNECT_BACKOFF_MULTIPLIER = 2.0;

    private final OkHttpClient okHttpClient;
    private final CoinglassProperties coinglassProperties;
    private final CoinglassLiquidationNormalizer liquidationNormalizer;
    private final MarketEventBus eventBus;
    private final RawMessagePersistenceService rawMessagePersistenceService;
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

    public OkHttpCoinglassWebSocketClient(OkHttpClient okHttpClient,
                                           CoinglassProperties coinglassProperties,
                                           CoinglassLiquidationNormalizer liquidationNormalizer,
                                           MarketEventBus eventBus,
                                           RawMessagePersistenceService rawMessagePersistenceService) {
        this.okHttpClient = okHttpClient;
        this.coinglassProperties = coinglassProperties;
        this.liquidationNormalizer = liquidationNormalizer;
        this.eventBus = eventBus;
        this.rawMessagePersistenceService = rawMessagePersistenceService;
    }

    @Override
    public void connect() {
        if (connected.get()) {
            log.warn("Coinglass WebSocket already connected, skipping");
            return;
        }
        String apiKey = coinglassProperties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.error("Coinglass API key not configured. Set COINGLASS_API_KEY environment variable.");
            return;
        }
        manualDisconnect = false;

        String url = String.format(WS_URL_TEMPLATE, apiKey);
        log.info("Connecting to Coinglass WebSocket...");
        Request request = new Request.Builder().url(url).build();

        okHttpClient.newWebSocket(request, new CoinglassWebSocketListener());
    }

    @Override
    public void disconnect() {
        manualDisconnect = true;
        if (webSocket != null) {
            webSocket.close(1000, "Client disconnect");
            webSocket = null;
        }
        connected.set(false);
        log.info("Coinglass WebSocket disconnected");
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
        // Coinglass 使用 channel 订阅模式
        String channel = request.channelType().getCode();
        String message = """
                {"method":"subscribe","channels":["%s"]}
                """.formatted(channel);
        webSocket.send(message);
        log.info("Subscribed to Coinglass channel: {}", channel);
    }

    @Override
    public void unsubscribe(SubscriptionRequest request) {
        if (!isConnected() || webSocket == null) {
            return;
        }
        String channel = request.channelType().getCode();
        String message = """
                {"method":"unsubscribe","channels":["%s"]}
                """.formatted(channel);
        webSocket.send(message);
        log.info("Unsubscribed from Coinglass channel: {}", channel);
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
     * 发送消息（供 Service 层调用，如订阅 liquidationOrders 频道）。
     */
    public void sendMessage(String message) {
        if (isConnected() && webSocket != null) {
            webSocket.send(message);
            log.debug("Sent message: {}", message);
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

            // 保存原始消息（不阻塞主流程）
            try {
                rawMessagePersistenceService.saveRawMessage(
                        "coinglass", "liquidationOrders", "ALL", text);
            } catch (Exception e) {
                log.warn("Failed to save raw message: {}", e.getMessage());
            }

            CgResultDTO<LiquidationOrder> result = objectMapper.readValue(
                    text, new TypeReference<CgResultDTO<LiquidationOrder>>() {});
            handleData(result);
        } catch (Exception e) {
            log.error("Error processing Coinglass message: {}", e.getMessage(), e);
            lastError.set(e.getMessage());
        }
    }

    private void handleData(CgResultDTO<LiquidationOrder> result) {
        try {
            List<LiquidationOrder> orderList = result.getData();
            if (orderList == null) return;

            for (LiquidationOrder order : orderList) {
                if (order == null) continue;

                LiquidationEvent event = liquidationNormalizer.normalize(order);
                if (event != null) {
                    eventBus.publish(event);
                    for (Consumer<MarketEvent> handler : eventHandlers) {
                        try {
                            handler.accept(event);
                        } catch (Exception e) {
                            log.error("Event handler error: {}", e.getMessage(), e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error parsing Coinglass data: {}", e.getMessage(), e);
            lastError.set(e.getMessage());
        }
    }

    private void scheduleReconnect() {
        if (manualDisconnect) {
            return;
        }
        connected.set(false);

        Thread existing = reconnectThread;
        if (existing != null && existing.isAlive()) {
            return;
        }

        reconnectThread = new Thread(() -> {
            long delay = INITIAL_RECONNECT_DELAY_MS;
            while (!manualDisconnect && !connected.get()) {
                try {
                    log.info("Reconnecting to Coinglass in {} ms...", delay);
                    Thread.sleep(delay);
                    reconnectCount.incrementAndGet();
                    connect();
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
        }, "coinglass-reconnect");
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    /**
     * OkHttp WebSocket 监听器。
     */
    private class CoinglassWebSocketListener extends WebSocketListener {

        @Override
        public void onOpen(@NotNull WebSocket ws, @NotNull Response response) {
            webSocket = ws;
            connected.set(true);
            lastError.set(null);
            log.info("Coinglass WebSocket connected");
        }

        @Override
        public void onMessage(@NotNull WebSocket ws, @NotNull String text) {
            handleMessage(text);
        }

        @Override
        public void onMessage(@NotNull WebSocket ws, @NotNull ByteString bytes) {
            handleMessage(bytes.utf8());
        }

        @Override
        public void onClosing(@NotNull WebSocket ws, int code, @NotNull String reason) {
            log.info("Coinglass WebSocket closing: {} {}", code, reason);
            ws.close(code, reason);
            connected.set(false);
            scheduleReconnect();
        }

        @Override
        public void onFailure(@NotNull WebSocket ws, @NotNull Throwable t, Response response) {
            log.error("Coinglass WebSocket failure: {}", t.getMessage(), t);
            lastError.set(t.getMessage());
            connected.set(false);
            scheduleReconnect();
        }
    }
}
