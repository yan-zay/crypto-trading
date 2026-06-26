package com.tj.crypto.central;

import com.tj.crypto.enums.Indicator;
import com.tj.crypto.enums.Symbol;
import com.tj.crypto.event.MarketEventBus;
import com.tj.crypto.marketdata.model.MarketEvent;
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
 * 支持两种策略接口：
 * - 新接口：Strategy（推荐）— 基于 MarketEvent + StrategyContext
 * - 旧接口：BaseStrategy（已废弃）— 基于 Symbol + Indicator
 *
 * 分发逻辑：
 * 1. 遍历所有新策略，检查 listenedEvents() 是否包含事件类型
 * 2. 匹配的策略在 tjTaskExecutor 线程池中异步执行
 * 3. 策略异常不传播，由 try-catch 隔离
 */
@Slf4j
@Component
public class StrategyEngine {

    private final ThreadPoolTaskExecutor tjTaskExecutor;
    private final MarketEventBus eventBus;
    private final StrategyContext strategyContext;

    /** 新策略接口 */
    private final List<Strategy> strategies;
    /** 旧策略接口（已废弃，保留兼容） */
    private final List<BaseStrategy> legacyStrategies;

    public StrategyEngine(ThreadPoolTaskExecutor tjTaskExecutor,
                          MarketEventBus eventBus,
                          StrategyContext strategyContext,
                          List<Strategy> strategies,
                          List<BaseStrategy> legacyStrategies) {
        this.tjTaskExecutor = tjTaskExecutor;
        this.eventBus = eventBus;
        this.strategyContext = strategyContext;
        this.strategies = strategies;
        this.legacyStrategies = legacyStrategies;
    }

    @PostConstruct
    public void init() {
        eventBus.subscribe(MarketEvent.class, this::onMarketEvent);
        log.info("StrategyEngine initialized: {} new strategies, {} legacy strategies",
                strategies.size(), legacyStrategies.size());
    }

    /**
     * 标准化市场事件入口。
     * 由 MarketEventBus 调用，分发到匹配的策略。
     */
    public void onMarketEvent(MarketEvent event) {
        // 新策略接口：按 listenedEvents() 匹配
        for (Strategy strategy : strategies) {
            if (strategy.listenedEvents().contains(event.getClass())) {
                tjTaskExecutor.execute(() -> {
                    try {
                        strategy.onEvent(event, strategyContext);
                    } catch (Exception e) {
                        log.error("Strategy {} error: {}", strategy.name(), e.getMessage(), e);
                    }
                });
            }
        }

        // 旧策略接口兼容（已废弃）
        for (BaseStrategy legacy : legacyStrategies) {
            try {
                if (legacy instanceof LiquidationSpikeStrategy liqStrategy) {
                    tjTaskExecutor.execute(() -> liqStrategy.onMarketEvent(event));
                }
            } catch (Exception e) {
                log.error("Legacy strategy {} error: {}",
                        legacy.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
    }

    /**
     * 旧的事件入口，保留兼容性。
     * @deprecated 使用 onMarketEvent(MarketEvent) 替代
     */
    @Deprecated
    public void callOnEvent(Symbol symbol, Indicator indicator) {
        legacyStrategies.forEach(strategy -> {
            if (strategy.getListenSymbol().contains(symbol) && strategy.getListenIndicator().contains(indicator)) {
                tjTaskExecutor.execute(() -> strategy.onEvent(symbol, indicator));
            }
        });
    }
}
