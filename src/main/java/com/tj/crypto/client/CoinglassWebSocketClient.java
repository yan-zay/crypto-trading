package com.tj.crypto.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.config.properties.CoinglassProperties;
import com.tj.crypto.config.properties.WebsocketProperties;
import com.tj.crypto.event.MarketEventBus;
import com.tj.crypto.marketdata.model.LiquidationEvent;
import com.tj.crypto.marketdata.normalize.CoinglassLiquidationNormalizer;
import com.tj.crypto.pojo.dto.CgResultDTO;
import com.tj.crypto.pojo.dto.KLineData;
import com.tj.crypto.pojo.dto.LiquidationOrder;
import jakarta.websocket.*;
import lombok.extern.slf4j.Slf4j;
import org.glassfish.tyrus.client.ClientManager;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Coinglass WebSocket 客户端。
 * 连接 Coinglass 爆仓数据流，接收并标准化为 LiquidationEvent。
 *
 * @Author zay
 * @Date 2025/9/15 17:19
 */
@Slf4j
@Component
@ClientEndpoint
public class CoinglassWebSocketClient {

    public static Session session;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ClientManager clientManager;
    private final CoinglassProperties coinglassProperties;
    private final CoinglassLiquidationNormalizer liquidationNormalizer;
    private final MarketEventBus eventBus;

    private final CountDownLatch connectionLatch = new CountDownLatch(1);
    private final AtomicReference<String> lastMessage = new AtomicReference<>();
    private WebsocketProperties websocketProperties;

    public CoinglassWebSocketClient(ClientManager clientManager,
                                     CoinglassProperties coinglassProperties,
                                     CoinglassLiquidationNormalizer liquidationNormalizer,
                                     MarketEventBus eventBus,
                                     WebsocketProperties websocketProperties) {
        this.clientManager = clientManager;
        this.coinglassProperties = coinglassProperties;
        this.liquidationNormalizer = liquidationNormalizer;
        this.eventBus = eventBus;
        this.websocketProperties = websocketProperties;
    }

    public void connect() {
        try {
            String apiKey = coinglassProperties.getApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                log.error("Coinglass API key not configured. Set COINGLASS_API_KEY environment variable.");
                return;
            }
            String wsUrl = "wss://open-ws.coinglass.com/ws-api?cg-api-key=" + apiKey;
            log.info("Attempting to connect to coinglass WebSocket via proxy...");
            session = clientManager.connectToServer(this, new URI(wsUrl));

            if (connectionLatch.await(10, TimeUnit.SECONDS)) {
                log.info("Successfully connected to coinglass WebSocket via proxy");
            } else {
                log.error("Connection timeout");
            }
        } catch (Exception e) {
            log.error("Failed to connect to WebSocket", e);
        }
    }

    @OnOpen
    public void onOpen(Session session, EndpointConfig config) {
        log.info("WebSocket connection opened via proxy: {}", session.getId());
        connectionLatch.countDown();
    }
    @OnMessage
    public void onMessage(String message) {
        try {
            log.info("Received raw message: {}", message);
            lastMessage.set(message);
            // 解析JSON消息
            CgResultDTO<LiquidationOrder> obj = objectMapper.readValue(message, new TypeReference<CgResultDTO<LiquidationOrder>>() {});
            handleData(obj);
            // 可以添加其他事件类型的处理

        } catch (Exception e) {
            log.error("Error processing message", e);
        }
    }

    /** 支持的交易对基础资产（Coinglass 发送的 symbol 格式不统一，按 baseAsset 过滤更可靠） */
    private static final List<String> supportedBaseAssets = List.of("BTC", "ETH", "BNB", "SOL", "DOGE", "TRUMP");

    private final Map<String, ConcurrentLinkedDeque<KLineData>> symbolOneMinuteData = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentLinkedDeque<KLineData>> symbolFiveMinuteData = new ConcurrentHashMap<>();

    private void handleData(CgResultDTO<LiquidationOrder> result) {
        try {
            List<LiquidationOrder> orderList = result.getData();
            for (LiquidationOrder order : orderList) {
                if (order != null) {
                    // Coinglass symbol 格式不统一（"BTCUSDT", "BTC-USDT-SWAP", "BTC-USD"）
                    // 按 baseAsset 过滤更可靠
                    String baseAsset = order.getBaseAsset();
                    if (baseAsset == null || !supportedBaseAssets.contains(baseAsset)) {
                        continue;
                    }

                    String symbol = order.getSymbol();
                    BigDecimal volUsd = order.getVolUsd();
                    long timestamp = order.getTime();

                    // 处理1分钟维度数据（保留现有聚合逻辑）
                    updateKLineData(symbolOneMinuteData, symbol, timestamp, volUsd, 60 * 1000);

                    // 处理5分钟维度数据（保留现有聚合逻辑）
                    updateKLineData(symbolFiveMinuteData, symbol, timestamp, volUsd, 5 * 60 * 1000);

                    // 标准化为 LiquidationEvent 并发布到事件总线
                    LiquidationEvent event = liquidationNormalizer.normalize(order);
                    if (event != null) {
                        eventBus.publish(event);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error parsing data", e);
        }
    }

    private void updateKLineData(Map<String, ConcurrentLinkedDeque<KLineData>> dataMap,
                                 String symbol, long timestamp, BigDecimal volume, long windowSize) {
        ConcurrentLinkedDeque<KLineData> deque = dataMap.computeIfAbsent(symbol, k -> new ConcurrentLinkedDeque<>());

        // 计算当前时间窗口的起始时间
        long currentWindowStart = timestamp / windowSize * windowSize;

        // 检查队列头部元素是否属于同一时间窗口
        KLineData firstData = deque.peekFirst();
        if (firstData != null && firstData.getTime() == currentWindowStart) {
            // 同一时间窗口，累加数量
            firstData.setQuantity(firstData.getQuantity().add(volume));
        } else {
            // 不同时间窗口，创建新对象
            KLineData newData = new KLineData();
            newData.setTime(currentWindowStart);
            newData.setQuantity(volume);
            deque.offerFirst(newData);
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
