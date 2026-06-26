package com.tj.crypto.backtest.paper;

import com.tj.crypto.backtest.portfolio.VirtualAccount;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.event.MarketEventBus;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.strategy.core.SignalCollector;
import com.tj.crypto.strategy.core.SignalEvent;
import com.tj.crypto.strategy.core.SignalType;
import com.tj.crypto.strategy.core.Strategy;
import com.tj.crypto.strategy.core.StrategyContext;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 模拟交易引擎。
 * 使用实时市场数据和虚拟资金验证策略。
 *
 * 与回测的区别：
 * - 回测：从历史数据回放，同步执行
 * - 模拟：从实时 WebSocket 接收，异步执行
 *
 * 共享组件：
 * - 同一套 Strategy 接口
 * - 同一套 MarketEvent 模型
 * - 同一套 VirtualAccount
 */
@Slf4j
@Component
public class PaperTradingEngine {

    private final MarketEventBus eventBus;
    private final StrategyContext strategyContext;
    private final SignalCollector signalCollector;
    private final java.util.List<Strategy> strategies;

    private VirtualAccount account;
    private boolean running = false;

    public PaperTradingEngine(MarketEventBus eventBus, StrategyContext strategyContext,
                              SignalCollector signalCollector, java.util.List<Strategy> strategies) {
        this.eventBus = eventBus;
        this.strategyContext = strategyContext;
        this.signalCollector = signalCollector;
        this.strategies = strategies;
    }

    /**
     * 启动模拟交易。
     *
     * @param initialBalance 初始资金
     */
    public void start(BigDecimal initialBalance) {
        if (running) {
            log.warn("Paper trading already running");
            return;
        }

        account = new VirtualAccount(initialBalance);
        running = true;

        // 订阅 BarEvent
        eventBus.subscribe(BarEvent.class, this::onBar);

        log.info("Paper trading started with balance ${}", initialBalance);
    }

    /**
     * 停止模拟交易。
     */
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
                        executeSignal(signal, bar.close(), bar.metadata().exchangeTimestamp());
                    }
                } catch (Exception e) {
                    log.error("Paper trading strategy error: {}", e.getMessage(), e);
                }
            }
        }
    }

    private void executeSignal(SignalEvent signal, BigDecimal currentPrice, long timestamp) {
        if (signal.type() == SignalType.BUY && !account.hasPosition(signal.instrument())) {
            BigDecimal quantity = account.getBalance().divide(currentPrice, 6, java.math.RoundingMode.HALF_UP);
            if (quantity.compareTo(BigDecimal.ZERO) > 0) {
                account.openPosition(signal.instrument(), OrderSide.LONG, quantity, currentPrice, timestamp);
                log.info("[PAPER] BUY {} {} @ ${}", quantity, signal.instrument().symbol(), currentPrice);
            }
        } else if (signal.type() == SignalType.SELL && account.hasPosition(signal.instrument())) {
            account.closePosition(signal.instrument(), currentPrice, timestamp);
            log.info("[PAPER] SELL {} @ ${}", signal.instrument().symbol(), currentPrice);
        }
    }

    public boolean isRunning() { return running; }
    public VirtualAccount getAccount() { return account; }
}
