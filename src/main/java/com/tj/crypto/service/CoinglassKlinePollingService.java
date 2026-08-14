package com.tj.crypto.service;

import com.tj.crypto.client.CoinglassKlinePollingConnector;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

/** Spring lifecycle bridge for CoinGlass incremental candle polling. */
@Service
@RequiredArgsConstructor
public class CoinglassKlinePollingService {
    private final CoinglassKlinePollingConnector connector;
    private final ThreadPoolTaskExecutor tjTaskExecutor;

    @PostConstruct
    public void start() {
        tjTaskExecutor.execute(connector::connect);
    }

    @PreDestroy
    public void stop() {
        connector.disconnect();
    }
}
