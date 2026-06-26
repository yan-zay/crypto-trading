package com.tj.crypto.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.config.properties.WebsocketProperties;
import com.tj.crypto.event.MarketEventBus;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.normalize.BinanceKlineNormalizer;
import jakarta.websocket.*;
import lombok.extern.slf4j.Slf4j;
import org.glassfish.tyrus.client.ClientManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Binance WebSocket 客户端。
 * 连接 Binance 期货 WebSocket，接收 kline 数据并标准化为 BarEvent。
 *
 * @Author zay
 * @Date 2025/9/15 17:19
 */
@Slf4j
@Component
@ClientEndpoint
@ConditionalOnProperty(name = "crypto.websocket.client-type", havingValue = "tyrus", matchIfMissing = true)
public class BinanceWebSocketClient {

    public static Session session;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ClientManager clientManager;
    private final BinanceKlineNormalizer klineNormalizer;
    private final MarketEventBus eventBus;

    private final CountDownLatch connectionLatch = new CountDownLatch(1);
    private final AtomicReference<String> lastMessage = new AtomicReference<>();
    private WebsocketProperties websocketProperties;

    public BinanceWebSocketClient(ClientManager clientManager,
                                   BinanceKlineNormalizer klineNormalizer,
                                   MarketEventBus eventBus,
                                   WebsocketProperties websocketProperties) {
        this.clientManager = clientManager;
        this.klineNormalizer = klineNormalizer;
        this.eventBus = eventBus;
        this.websocketProperties = websocketProperties;
    }

    public void connect() {
        try {
            log.info("Attempting to connect to Binance WebSocket via proxy...");
            session = clientManager.connectToServer(this, new URI(websocketProperties.getWebsocketUrl()));

            if (connectionLatch.await(10, TimeUnit.SECONDS)) {
                log.info("Successfully connected to Binance WebSocket via proxy");
            } else {
                log.error("Connection timeout");
            }
        } catch (Exception e) {
            log.error("Failed to connect to WebSocket", e);
        }
    }

    @OnOpen
    public void onOpen(Session session, EndpointConfig config) { // 注意添加了EndpointConfig参数
        log.info("WebSocket connection opened via proxy: {}", session.getId());
        connectionLatch.countDown();
    }

    @OnMessage
    public void onMessage(String message) {
        try {
            log.info("111Received raw message: {}", message);
            lastMessage.set(message);

            // 解析JSON消息
            JsonNode jsonNode = objectMapper.readTree(message);

            // 处理K线数据
            if (jsonNode.has("e") && "kline".equals(jsonNode.get("e").asText())) {
                handleKlineData(jsonNode);
            }
            // 可以添加其他事件类型的处理

        } catch (Exception e) {
            log.error("Error processing message", e);
        }
    }

    private void handleKlineData(JsonNode jsonNode) {
        try {
            JsonNode kline = jsonNode.get("k");
            long eventTime = jsonNode.has("E") ? jsonNode.get("E").asLong() : System.currentTimeMillis();

            // 标准化为 BarEvent 并发布到事件总线
            BarEvent barEvent = klineNormalizer.normalize(kline, eventTime);
            if (barEvent != null) {
                eventBus.publish(barEvent);

                log.debug("BarEvent published: {} {} O={} H={} L={} C={} V={} closed={}",
                        barEvent.instrument().symbol(),
                        barEvent.timeframe().getCode(),
                        barEvent.open(), barEvent.high(), barEvent.low(), barEvent.close(),
                        barEvent.volume(), barEvent.closed());
            }

        } catch (Exception e) {
            log.error("Error parsing kline data", e);
        }
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) {
        log.info("WebSocket connection closed: {}", reason);
        // 实现重连逻辑
        reconnect();
    }

    @OnError
    public void onError(Session session, Throwable throwable) { // 确保包含Throwable参数
        log.error("WebSocket error occurred", throwable);
    }

    private void reconnect() {
        new Thread(() -> {
            try {
                Thread.sleep(5000);
                log.info("Attempting to reconnect...");
                connect();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Reconnect thread interrupted", e);
            }
        }).start();
    }

    // 发送消息的方法
    public void sendMessage(String message) {
        if (isConnected()) {
            try {
                session.getBasicRemote().sendText(message);
                log.debug("Sent message: {}", message);
            } catch (Exception e) {
                log.error("Failed to send message", e);
            }
        } else {
            log.warn("Cannot send message, connection is not open");
        }
    }

    public boolean isConnected() {
        return session != null && session.isOpen();
    }

    public String getLastMessage() {
        return lastMessage.get();
    }
}
