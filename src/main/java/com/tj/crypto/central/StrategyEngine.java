package com.tj.crypto.central;

import com.tj.crypto.enums.Indicator;
import com.tj.crypto.enums.Symbol;
import com.tj.crypto.event.MarketEventBus;
import com.tj.crypto.marketdata.model.LiquidationEvent;
import com.tj.crypto.marketdata.model.MarketEvent;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 策略引擎。
 * 接收市场事件，按策略的监听条件分发到对应策略执行。
 *
 * 事件入口：
 * - onMarketEvent(MarketEvent) — 新的标准化事件入口，由 MarketEventBus 调用
 * - callOnEvent(Symbol, Indicator) — 旧的事件入口，保留兼容性
 *
 * 设计决策：
 * - 在 @PostConstruct 中注册到 MarketEventBus，订阅所有 MarketEvent
 * - 使用 tjTaskExecutor 异步执行策略，避免阻塞事件总线
 * - 策略异常不传播，由 try-catch 隔离
 */
@Slf4j
@Component
public class StrategyEngine {

    private final ThreadPoolTaskExecutor tjTaskExecutor;
    private final List<BaseStrategy> strategyList;
    private final MarketEventBus eventBus;

    public StrategyEngine(ThreadPoolTaskExecutor tjTaskExecutor,
                          List<BaseStrategy> strategyList,
                          MarketEventBus eventBus) {
        this.tjTaskExecutor = tjTaskExecutor;
        this.strategyList = strategyList;
        this.eventBus = eventBus;
    }

    @PostConstruct
    public void init() {
        // 订阅所有 MarketEvent，由本引擎负责分发到具体策略
        eventBus.subscribe(MarketEvent.class, this::onMarketEvent);
        log.info("StrategyEngine initialized, registered {} strategies", strategyList.size());
    }

    /**
     * 标准化市场事件入口。
     * 由 MarketEventBus 调用，分发到匹配的策略。
     *
     * @param event 市场事件
     */
    public void onMarketEvent(MarketEvent event) {
        for (BaseStrategy strategy : strategyList) {
            try {
                // 检查策略是否支持 onMarketEvent（LiquidationSpikeStrategy）
                if (strategy instanceof LiquidationSpikeStrategy liqStrategy) {
                    tjTaskExecutor.execute(() -> liqStrategy.onMarketEvent(event));
                }
                // 旧接口兼容：按 Symbol + Indicator 匹配
                // 后续阶段将统一为新的 Strategy 接口
            } catch (Exception e) {
                log.error("Strategy {} error processing event: {}",
                        strategy.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
    }

    /**
     * 旧的事件入口，保留兼容性。
     * 由旧的 EventBus 或手动调用使用。
     */
    public void callOnEvent(Symbol symbol, Indicator indicator) {
        strategyList.forEach(strategy -> {
            if (strategy.getListenSymbol().contains(symbol) && strategy.getListenIndicator().contains(indicator)) {
                tjTaskExecutor.execute(() -> strategy.onEvent(symbol, indicator));
            }
        });
    }
}
