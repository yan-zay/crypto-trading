package com.tj.crypto.backtest.engine;

import com.tj.crypto.backtest.data.HistoricalDataProvider;
import com.tj.crypto.backtest.portfolio.VirtualAccount;
import com.tj.crypto.backtest.report.PerformanceCalculator;
import com.tj.crypto.backtest.report.PerformanceReport;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.event.InMemoryEventBus;
import com.tj.crypto.event.MarketEventBus;
import com.tj.crypto.factor.cache.BarCache;
import com.tj.crypto.factor.cache.InMemoryBarCache;
import com.tj.crypto.factor.core.FactorRegistry;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.MarketEvent;
import com.tj.crypto.strategy.core.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 回测引擎。
 * 使用独立的 EventBus、BarCache 和 StrategyContext 运行回测。
 *
 * 关键设计：
 * - 回测使用独立的 InMemoryEventBus，不影响全局
 * - 回测同步执行（事件总线同步派发），确保确定性
 * - 复用同一套 Strategy 接口和 MarketEvent 模型
 */
@Slf4j
@Component
public class BacktestEngine {

    private final PerformanceCalculator performanceCalculator;

    public BacktestEngine(PerformanceCalculator performanceCalculator) {
        this.performanceCalculator = performanceCalculator;
    }

    /**
     * 运行回测。
     *
     * @param config     回测配置
     * @param strategy   策略
     * @param dataProvider 历史数据提供者
     * @return 回测结果
     */
    public BacktestResult run(BacktestConfig config, Strategy strategy, HistoricalDataProvider dataProvider) {
        log.info("Starting backtest: {} {} [{}, {}] initialBalance=${}",
                config.instrument().symbol(), config.timeframe().getCode(),
                config.startTime(), config.endTime(), config.initialBalance());

        // 1. 创建独立的组件（不污染全局）
        InMemoryEventBus backtestEventBus = new InMemoryEventBus();
        InMemoryBarCache backtestBarCache = new InMemoryBarCache(backtestEventBus);

        // 2. 创建回测专用 StrategyContext
        StrategyContext backtestContext = new BacktestStrategyContext(backtestBarCache);

        // 3. 创建虚拟账户
        VirtualAccount account = new VirtualAccount(config.initialBalance());

        // 4. 收集信号和交易
        List<SignalEvent> signals = new ArrayList<>();

        // 5. 订阅 BarEvent，自动执行交易
        backtestEventBus.subscribe(BarEvent.class, bar -> {
            backtestBarCache.addBar(bar);

            // 只在 closed bar 时触发策略
            if (!bar.closed()) return;

            SignalEvent signal = strategy.onEvent(bar, backtestContext);
            if (signal != null) {
                signals.add(signal);
                executeSignal(account, signal, bar.close(), bar.metadata().exchangeTimestamp());
            }
        });

        // 6. 回放历史事件
        EventReplayer replayer = new EventReplayer(backtestEventBus);
        int eventCount = replayer.replay(dataProvider, config.instrument(),
                config.timeframe(), config.startTime(), config.endTime());

        // 7. 平仓所有持仓
        closeAllPositions(account, config.instrument(), config.endTime());

        // 8. 计算性能报告
        BigDecimal finalBalance = account.getBalance();
        PerformanceReport report = performanceCalculator.calculate(
                account.getTrades(), config.initialBalance(), finalBalance,
                config.startTime(), config.endTime());

        log.info("Backtest complete: {} events, {} signals, {} trades, {}",
                eventCount, signals.size(), account.getTrades().size(), report);

        return new BacktestResult(config, signals, account.getTrades(), report, finalBalance);
    }

    private void executeSignal(VirtualAccount account, SignalEvent signal,
                               BigDecimal currentPrice, long timestamp) {
        if (signal.type() == SignalType.BUY && !account.hasPosition(signal.instrument())) {
            // 简单策略：全仓买入
            BigDecimal quantity = account.getBalance().divide(currentPrice, 6, BigDecimal.ROUND_HALF_UP);
            if (quantity.compareTo(BigDecimal.ZERO) > 0) {
                account.openPosition(signal.instrument(), OrderSide.LONG, quantity, currentPrice, timestamp);
            }
        } else if (signal.type() == SignalType.SELL && account.hasPosition(signal.instrument())) {
            account.closePosition(signal.instrument(), currentPrice, timestamp);
        }
    }

    private void closeAllPositions(VirtualAccount account, com.tj.crypto.common.domain.Instrument instrument, long timestamp) {
        if (account.hasPosition(instrument)) {
            // 使用最后的价格平仓（简化处理）
            account.closePosition(instrument, BigDecimal.ZERO, timestamp);
        }
    }

    /**
     * 回测专用 StrategyContext。
     * 使用回测专用的 BarCache。
     */
    private record BacktestStrategyContext(InMemoryBarCache barCache) implements StrategyContext {
        @Override
        public com.tj.crypto.factor.core.Factor getFactor(String name,
                                                            com.tj.crypto.common.domain.Instrument instrument,
                                                            com.tj.crypto.common.domain.Timeframe timeframe) {
            // 回测时因子计算需要通过 FactorRegistry，但这里简化处理
            // 实际应该创建回测专用的 FactorRegistry
            return null;
        }

        @Override
        public List<com.tj.crypto.factor.core.Factor> getAllFactors(
                com.tj.crypto.common.domain.Instrument instrument,
                com.tj.crypto.common.domain.Timeframe timeframe) {
            return List.of();
        }
    }
}
