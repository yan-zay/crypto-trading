package com.tj.crypto.service;

import com.tj.crypto.client.OkHttpBinanceWebSocketClient;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

import static com.tj.crypto.config.ThreadPoolConfig.THREAD_POOL_NAME;

/**
 * Binance WebSocket 生命周期管理（OkHttp 实现）。
 * 连接 Binance 期货 WebSocket，订阅 K 线流。
 *
 * <p>通过 {@code crypto.websocket.client-type=okhttp} 启用。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "crypto.websocket.client-type", havingValue = "okhttp")
public class OkHttpBinanceWebSocketService {

    private final OkHttpBinanceWebSocketClient webSocketClient;
    private final ThreadPoolTaskExecutor tjTaskExecutor;
    private final List<String> symbols;

    public OkHttpBinanceWebSocketService(OkHttpBinanceWebSocketClient webSocketClient,
                                          ThreadPoolTaskExecutor tjTaskExecutor,
                                          @Value("${crypto.binance.symbols}") List<String> symbols) {
        this.webSocketClient = webSocketClient;
        this.tjTaskExecutor = tjTaskExecutor;
        this.symbols = symbols;
    }

    @PostConstruct
    public void init() {
        log.info("Initializing OkHttp Binance WebSocket service...");
        tjTaskExecutor.execute(() -> {
            webSocketClient.connect();
            // 连接成功后订阅 K 线流（OkHttp 客户端在 URL 中已包含订阅，但也可手动订阅额外流）
            try {
                Thread.sleep(3000);
                if (webSocketClient.isConnected()) {
                    subscribeKlineStreams();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Async(THREAD_POOL_NAME)
    @Scheduled(fixedRate = 5000)
    public void checkConnection() {
        if (!webSocketClient.isConnected()) {
            log.warn("Binance WebSocket (OkHttp) connection is down, will auto-reconnect");
            // OkHttp 客户端内置指数退避重连，无需手动触发
        }
    }

    /**
     * 订阅 Binance 期货 K 线流。
     */
    private void subscribeKlineStreams() {
        String params = symbols.stream()
                .map(s -> "\"" + s.toLowerCase() + "@kline_1m\"")
                .collect(Collectors.joining(", "));
        String subscribeMessage = """
                {"method":"SUBSCRIBE","params":[%s],"id":1}
                """.formatted(params);
        webSocketClient.sendSubscribeMessage(subscribeMessage);
        log.info("Subscribed to Binance kline streams via OkHttp: {}", symbols);
    }
}
