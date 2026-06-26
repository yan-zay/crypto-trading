package com.tj.crypto.service;

import com.tj.crypto.client.BinanceWebSocketClient;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import static com.tj.crypto.config.ThreadPoolConfig.THREAD_POOL_NAME;

/**
 * Binance WebSocket 生命周期管理。
 * 连接 Binance 期货 WebSocket，订阅 BTC/ETH 1min K 线。
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

    @PostConstruct
    public void init() {
        log.info("Initializing Binance WebSocket service...");
        tjTaskExecutor.execute(() -> {
            webSocketClient.connect();
            // 连接成功后订阅 K 线流
            try {
                Thread.sleep(3000);
                subscribeKlineStreams();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Async(THREAD_POOL_NAME)
    @Scheduled(fixedRate = 5000)
    public void checkConnection() {
        if (!webSocketClient.isConnected()) {
            log.warn("Binance WebSocket connection is down, attempting to reconnect...");
            webSocketClient.connect();
            try {
                Thread.sleep(3000);
                subscribeKlineStreams();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 订阅 Binance 期货 K 线流。
     * 使用 /market 路径（Binance USD-M Futures 新路由）。
     */
    private void subscribeKlineStreams() {
        String subscribeMessage = """
                {
                    "method": "SUBSCRIBE",
                    "params": [
                        "btcusdt@kline_1m",
                        "ethusdt@kline_1m"
                    ],
                    "id": 1
                }
                """;
        webSocketClient.sendMessage(subscribeMessage);
        log.info("Subscribed to Binance kline streams: btcusdt@1m, ethusdt@1m");
    }
}
