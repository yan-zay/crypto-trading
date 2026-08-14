package com.tj.crypto.service;

import com.tj.crypto.client.OkHttpBinanceWebSocketClient;
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
 * Binance WebSocket 生命周期管理（OkHttp 实现）。
 * 连接 Binance 期货 WebSocket，订阅 K 线流。
 *
 * <p>通过 {@code crypto.websocket.client-type=okhttp} 启用。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "crypto.connector.binance-enabled",
        havingValue = "true", matchIfMissing = true)
public class OkHttpBinanceWebSocketService {

    private final OkHttpBinanceWebSocketClient webSocketClient;
    private final ThreadPoolTaskExecutor tjTaskExecutor;

    public OkHttpBinanceWebSocketService(OkHttpBinanceWebSocketClient webSocketClient,
                                          ThreadPoolTaskExecutor tjTaskExecutor) {
        this.webSocketClient = webSocketClient;
        this.tjTaskExecutor = tjTaskExecutor;
    }

    @PostConstruct
    public void init() {
        log.info("Initializing OkHttp Binance WebSocket service...");
        tjTaskExecutor.execute(() -> {
            webSocketClient.connect();
        });
    }

    @PreDestroy
    public void shutdown() {
        webSocketClient.disconnect();
    }

    @Async(THREAD_POOL_NAME)
    @Scheduled(fixedRate = 5000)
    public void checkConnection() {
        if (!webSocketClient.isConnected()) {
            log.warn("Binance WebSocket (OkHttp) connection is down, will auto-reconnect");
            // OkHttp 客户端内置指数退避重连，无需手动触发
        }
    }

}
