package com.tj.crypto.service;

import com.tj.crypto.client.OkHttpCoinglassWebSocketClient;
import com.tj.crypto.config.properties.CoinglassProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import static com.tj.crypto.config.ThreadPoolConfig.THREAD_POOL_NAME;

/**
 * Coinglass WebSocket 生命周期管理（OkHttp 实现）。
 * 仅在 API key 配置时启动连接。
 *
 * <p>通过 {@code crypto.websocket.client-type=okhttp} 启用。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "crypto.websocket.client-type", havingValue = "okhttp", matchIfMissing = true)
public class OkHttpCgWebSocketService {

    private final OkHttpCoinglassWebSocketClient webSocketClient;
    private final CoinglassProperties coinglassProperties;
    private final ThreadPoolTaskExecutor tjTaskExecutor;
    private volatile boolean enabled = false;

    public OkHttpCgWebSocketService(OkHttpCoinglassWebSocketClient webSocketClient,
                                     CoinglassProperties coinglassProperties,
                                     ThreadPoolTaskExecutor tjTaskExecutor) {
        this.webSocketClient = webSocketClient;
        this.coinglassProperties = coinglassProperties;
        this.tjTaskExecutor = tjTaskExecutor;
    }

    @PostConstruct
    public void init() {
        if (!coinglassProperties.isWebsocketEnabled()) {
            log.info("Coinglass WebSocket service disabled by configuration");
            return;
        }
        String apiKey = coinglassProperties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Coinglass API key not configured, OkHttp WebSocket service disabled");
            return;
        }
        log.info("Initializing OkHttp Coinglass WebSocket service...");
        enabled = true;
        tjTaskExecutor.execute(() -> {
            webSocketClient.connect();
        });
    }

    @Async(THREAD_POOL_NAME)
    @Scheduled(fixedRate = 3000)
    public void checkConnection() {
        if (!enabled) return;
        if (!webSocketClient.isConnected()) {
            log.warn("Coinglass WebSocket (OkHttp) connection is down, will auto-reconnect");
            // OkHttp 客户端内置指数退避重连，无需手动触发
        }
    }

    @PreDestroy
    public void shutdown() {
        if (enabled) webSocketClient.disconnect();
    }

}
