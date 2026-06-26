package com.tj.crypto.backtest.paper;

import com.tj.crypto.backtest.portfolio.VirtualAccount;
import com.tj.crypto.event.MarketEventBus;
import com.tj.crypto.execution.ExecutionEngine;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.strategy.core.SignalCollector;
import com.tj.crypto.strategy.core.SignalEvent;
import com.tj.crypto.strategy.core.Strategy;
import com.tj.crypto.strategy.core.StrategyContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 模拟交易引擎。
 * 使用实时市场数据、虚拟资金和 ExecutionEngine 验证策略。
 *
 * 与回测共享：
 * - 同一套 Strategy 接口
 * - 同一套 ExecutionEngine（风控 + 仓位 + 滑点）
 * - 同一套 VirtualAccount
 */
@Slf4j
@Component
public class PaperTradingEngine {

    private final MarketEventBus eventBus;
    private final StrategyContext strategyContext;
    private final SignalCollector signalCollector;
    private final ExecutionEngine executionEngine;
    private final List<Strategy> strategies;

    private VirtualAccount account;
    private boolean running = false;

    public PaperTradingEngine(MarketEventBus eventBus, StrategyContext strategyContext,
                              SignalCollector signalCollector, ExecutionEngine executionEngine,
                              List<Strategy> strategies) {
        this.eventBus = eventBus;
        this.strategyContext = strategyContext;
        this.signalCollector = signalCollector;
        this.executionEngine = executionEngine;
        this.strategies = strategies;
    }

    public void start(BigDecimal initialBalance) {
        if (running) {
            log.warn("Paper trading already running");
            return;
        }
        account = new VirtualAccount(initialBalance);
        running = true;
        eventBus.subscribe(BarEvent.class, this::onBar);
        log.info("Paper trading started with balance ${}", initialBalance);
    }

    public void stop() {
        running = false;
        log.info("Paper trading stopped. Final balance: ${}", account.getBalance());
    }

    private void onBar(BarEvent bar) {
        if (!running || !bar.closed()) return;

        for (Strategy strategy : strategies) {
            if (strategy.listenedEvents().contains(BarEvent.class)) {
                try {
                    SignalEvent signal = strategy.onEvent(bar, strategyContext);
                    if (signal != null) {
                        signalCollector.collect(signal);
                        // 通过 ExecutionEngine 执行（含风控 + 仓位 + 滑点）
                        Order order = executionEngine.execute(signal, account,
                                bar.close(), bar.metadata().exchangeTimestamp());
                        if (order != null && order.isFilled()) {
                            log.info("[PAPER] {} {} @ ${}", order.side(), order.quantity(), order.price());
                        }
                    }
                } catch (Exception e) {
                    log.error("Paper trading strategy error: {}", e.getMessage(), e);
                }
            }
        }
    }

    public boolean isRunning() { return running; }
    public VirtualAccount getAccount() { return account; }
}
