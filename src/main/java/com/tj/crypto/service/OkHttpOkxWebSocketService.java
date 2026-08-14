package com.tj.crypto.service;

import com.tj.crypto.client.OkHttpOkxWebSocketClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

/** Starts and stops the optional OKX public market data connector. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "crypto.connector.okx.enabled", havingValue = "true")
public class OkHttpOkxWebSocketService {
    private final OkHttpOkxWebSocketClient client;
    private final ThreadPoolTaskExecutor tjTaskExecutor;

    @PostConstruct
    public void start() {
        tjTaskExecutor.execute(client::connect);
    }

    @PreDestroy
    public void stop() {
        client.disconnect();
    }
}
