package com.tj.crypto.service;

import com.tj.crypto.client.CoinglassWebSocketClient;
import com.tj.crypto.config.properties.CoinglassProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import static com.tj.crypto.config.ThreadPoolConfig.THREAD_POOL_NAME;

/**
 * Coinglass WebSocket 生命周期管理。
 * 仅在 API key 配置时启动连接。
 */
@Slf4j
@Service
public class CgWebSocketService {

    private final CoinglassWebSocketClient webSocketClient;
    private final CoinglassProperties coinglassProperties;
    private final ThreadPoolTaskExecutor tjTaskExecutor;
    private volatile boolean enabled = false;

    public CgWebSocketService(CoinglassWebSocketClient webSocketClient,
                              CoinglassProperties coinglassProperties,
                              ThreadPoolTaskExecutor tjTaskExecutor) {
        this.webSocketClient = webSocketClient;
        this.coinglassProperties = coinglassProperties;
        this.tjTaskExecutor = tjTaskExecutor;
    }

    @PostConstruct
    public void init() {
        String apiKey = coinglassProperties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Coinglass API key not configured, WebSocket service disabled");
            return;
        }
        log.info("Initializing Coinglass WebSocket service...");
        enabled = true;
        tjTaskExecutor.execute(() -> {
            webSocketClient.connect();
            // 连接成功后延迟订阅
            try {
                Thread.sleep(5000);
                subscribeToSymbol();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Async(THREAD_POOL_NAME)
    @Scheduled(fixedRate = 3000)
    public void checkConnection() {
        if (!enabled) return;
        if (!webSocketClient.isConnected()) {
            log.warn("Coinglass WebSocket connection is down, attempting to reconnect...");
            webSocketClient.connect();
        }
    }

    private void subscribeToSymbol() {
        String subscribeMessage = """
                {
                    "method": "subscribe",
                    "channels": ["liquidationOrders"]
                }
                """;
        webSocketClient.sendMessage(subscribeMessage);
    }
}
