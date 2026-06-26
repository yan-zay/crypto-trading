package com.tj.crypto.service;

import com.tj.crypto.client.BinanceWebSocketClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

/**
 * Binance WebSocket 生命周期管理。
 * 当前未启用（@PostConstruct 被注释），保留供后续使用。
 */
@Slf4j
@Service
public class BinanceWebSocketService {

    private final BinanceWebSocketClient webSocketClient;
    private final ThreadPoolTaskExecutor tjTaskExecutor;

    public BinanceWebSocketService(BinanceWebSocketClient webSocketClient,
                                    ThreadPoolTaskExecutor tjTaskExecutor) {
        this.webSocketClient = webSocketClient;
        this.tjTaskExecutor = tjTaskExecutor;
    }

/*    @PostConstruct
    public void init() {
        log.info("Initializing WebSocket service...");
        tjTaskExecutor.execute(webSocketClient::connect);
    }

    @Async(THREAD_POOL_NAME)
    @Scheduled(fixedRate = 3000) // 每30秒检查一次连接
    public void checkConnection() {
        log.info("Checking WebSocket connection...");
        if (!webSocketClient.isConnected()) {
            log.warn("WebSocket connection is down, attempting to reconnect...");
            webSocketClient.connect();
        }
    }*/


    // 示例：发送订阅消息
    public void subscribeToSymbol() {
        while (webSocketClient.isConnected()) {
            String subscribeMessage = "{" +
                    "    \"method\": \"subscribe\",\n" +
                    "    \"channels\": [\"liquidationOrders\"]\n" +
                    "}";
            webSocketClient.sendMessage(subscribeMessage);
        }
    }
}
