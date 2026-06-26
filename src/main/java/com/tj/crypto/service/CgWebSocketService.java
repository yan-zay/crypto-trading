package com.tj.crypto.service;

import com.tj.crypto.client.CoinglassWebSocketClient;
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
public class CgWebSocketService {

    private final CoinglassWebSocketClient webSocketClient;
    private ThreadPoolTaskExecutor tjTaskExecutor;

    @PostConstruct
    public void init() throws InterruptedException {
        log.info("Initializing WebSocket service...");
        tjTaskExecutor.execute(webSocketClient::connect);
        this.subscribeToSymbol();
    }

    @Async(THREAD_POOL_NAME)
    @Scheduled(fixedRate = 3000) // 每30秒检查一次连接
    public void checkConnection() {
//        log.info("Checking WebSocket connection...");
        if (!webSocketClient.isConnected()) {
            log.warn("WebSocket connection is down, attempting to reconnect...");
            webSocketClient.connect();
        }
    }


    // 示例：发送订阅消息
    public void subscribeToSymbol() throws InterruptedException {
        Thread.sleep(5000);
/*        while (!webSocketClient.isConnected()) {
            continue;
        }*/
        String subscribeMessage = """
                    {
                        "method": "subscribe",
                        "channels": ["liquidationOrders"]
                    }
                    """;
        webSocketClient.sendMessage(subscribeMessage);
    }
}
