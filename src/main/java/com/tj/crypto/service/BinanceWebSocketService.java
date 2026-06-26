package com.tj.crypto.service;

import com.tj.crypto.client.BinanceWebSocketClient;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import static com.tj.crypto.config.ThreadPoolConfig.THREAD_POOL_NAME;

/**
 * @Author zay
 * @Date 2025/9/12 16:25
 */
@Slf4j
@Service
@AllArgsConstructor
public class BinanceWebSocketService {

    private final BinanceWebSocketClient webSocketClient;
    private ThreadPoolTaskExecutor tjTaskExecutor;

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
