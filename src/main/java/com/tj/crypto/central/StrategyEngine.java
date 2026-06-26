package com.tj.crypto.central;

import com.tj.crypto.event.MarketEventBus;
import com.tj.crypto.marketdata.model.MarketEvent;
import com.tj.crypto.strategy.core.SignalCollector;
import com.tj.crypto.strategy.core.SignalEvent;
import com.tj.crypto.strategy.core.Strategy;
import com.tj.crypto.strategy.core.StrategyContext;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 策略引擎。
 * 接收市场事件，按策略的监听条件分发到对应策略执行。
 *
 * 分发逻辑：
 * 1. 遍历所有策略，检查 listenedEvents() 是否包含事件类型
 * 2. 匹配的策略在 tjTaskExecutor 线程池中异步执行
 * 3. 策略异常不传播，由 try-catch 隔离
 */
@Slf4j
@Component
public class StrategyEngine {

    private final ThreadPoolTaskExecutor tjTaskExecutor;
    private final MarketEventBus eventBus;
    private final StrategyContext strategyContext;
    private final SignalCollector signalCollector;

    private final List<Strategy> strategies;

    public StrategyEngine(ThreadPoolTaskExecutor tjTaskExecutor,
                          MarketEventBus eventBus,
                          StrategyContext strategyContext,
                          SignalCollector signalCollector,
                          List<Strategy> strategies) {
        this.tjTaskExecutor = tjTaskExecutor;
        this.eventBus = eventBus;
        this.strategyContext = strategyContext;
        this.signalCollector = signalCollector;
        this.strategies = strategies;
    }

    @PostConstruct
    public void init() {
        eventBus.subscribe(MarketEvent.class, this::onMarketEvent);
        log.info("StrategyEngine initialized: {} strategies", strategies.size());
    }

    /**
     * 标准化市场事件入口。
     * 由 MarketEventBus 调用，分发到匹配的策略。
     */
    public void onMarketEvent(MarketEvent event) {
        for (Strategy strategy : strategies) {
            if (strategy.listenedEvents().contains(event.getClass())) {
                tjTaskExecutor.execute(() -> {
                    try {
                        SignalEvent signal = strategy.onEvent(event, strategyContext);
                        if (signal != null) {
                            signalCollector.collect(signal);
                        }
                    } catch (Exception e) {
                        log.error("Strategy {} error: {}", strategy.name(), e.getMessage(), e);
                    }
                });
            }
        }
    }
}
