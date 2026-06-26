package com.tj.crypto.backtest.engine;

import com.tj.crypto.backtest.data.HistoricalDataProvider;
import com.tj.crypto.backtest.portfolio.VirtualAccount;
import com.tj.crypto.backtest.report.PerformanceCalculator;
import com.tj.crypto.backtest.report.PerformanceReport;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.event.InMemoryEventBus;
import com.tj.crypto.execution.ExecutionEngine;
import com.tj.crypto.factor.cache.InMemoryBarCache;
import com.tj.crypto.factor.core.Factor;
import com.tj.crypto.factor.core.FactorCalculator;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.strategy.core.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 回测引擎。
 * 使用独立的 EventBus、BarCache 和 StrategyContext 运行回测。
 *
 * 关键设计：
 * - 回测使用独立的 InMemoryEventBus，不影响全局
 * - 回测同步执行（事件总线同步派发），确保确定性
 * - 复用同一套 Strategy 接口和 MarketEvent 模型
 * - 使用最后一根 bar 的收盘价平仓（而非 0）
 */
@Slf4j
@Component
public class BacktestEngine {

    private final PerformanceCalculator performanceCalculator;
    private final List<FactorCalculator> factorCalculators;
    private final ExecutionEngine executionEngine;

    public BacktestEngine(PerformanceCalculator performanceCalculator,
                          List<FactorCalculator> factorCalculators,
                          ExecutionEngine executionEngine) {
        this.performanceCalculator = performanceCalculator;
        this.factorCalculators = factorCalculators;
        this.executionEngine = executionEngine;
    }

    /**
     * 运行回测。
     */
    public BacktestResult run(BacktestConfig config, Strategy strategy, HistoricalDataProvider dataProvider) {
        log.info("Starting backtest: {} {} [{}, {}] initialBalance=${}",
                config.instrument().symbol(), config.timeframe().getCode(),
                config.startTime(), config.endTime(), config.initialBalance());

        // 1. 创建独立的组件
        InMemoryEventBus backtestEventBus = new InMemoryEventBus();
        InMemoryBarCache backtestBarCache = new InMemoryBarCache(backtestEventBus);

        // 2. 创建回测专用 StrategyContext（带因子计算）
        StrategyContext backtestContext = new BacktestStrategyContext(backtestBarCache, factorCalculators);

        // 3. 创建虚拟账户
        VirtualAccount account = new VirtualAccount(config.initialBalance());

        // 4. 收集信号
        List<SignalEvent> signals = new ArrayList<>();

        // 5. 跟踪最后一根 bar 的收盘价
        AtomicReference<BigDecimal> lastClosePrice = new AtomicReference<>(BigDecimal.ZERO);

        // 6. 订阅 BarEvent
        backtestEventBus.subscribe(BarEvent.class, bar -> {
            backtestBarCache.addBar(bar);
            lastClosePrice.set(bar.close());

            if (!bar.closed()) return;

            SignalEvent signal = strategy.onEvent(bar, backtestContext);
            if (signal != null) {
                signals.add(signal);
                // 通过 ExecutionEngine 执行（含风控 + 仓位 + 滑点）
                executionEngine.execute(signal, account, bar.close(), bar.metadata().exchangeTimestamp());
            }
        });

        // 7. 回放历史事件
        EventReplayer replayer = new EventReplayer(backtestEventBus);
        int eventCount = replayer.replay(dataProvider, config.instrument(),
                config.timeframe(), config.startTime(), config.endTime());

        // 8. 使用最后一根 bar 的收盘价平仓（而非 0）
        closeAllPositions(account, config.instrument(), lastClosePrice.get(), config.endTime());

        // 9. 计算性能报告
        BigDecimal finalBalance = account.getBalance();
        PerformanceReport report = performanceCalculator.calculate(
                account.getTrades(), config.initialBalance(), finalBalance,
                config.startTime(), config.endTime());

        log.info("Backtest complete: {} events, {} signals, {} trades, {}",
                eventCount, signals.size(), account.getTrades().size(), report);

        return new BacktestResult(config, signals, account.getTrades(), report, finalBalance);
    }

    private void closeAllPositions(VirtualAccount account, Instrument instrument,
                                   BigDecimal lastPrice, long timestamp) {
        if (account.hasPosition(instrument)) {
            account.closePosition(instrument, lastPrice, timestamp);
        }
    }

    /**
     * 回测专用 StrategyContext。
     * 使用回测专用的 BarCache 和 FactorCalculator 列表。
     */
    private record BacktestStrategyContext(
            InMemoryBarCache barCache,
            List<FactorCalculator> calculators
    ) implements StrategyContext {

        @Override
        public Factor getFactor(String name, Instrument instrument, Timeframe timeframe) {
            for (FactorCalculator calc : calculators) {
                if (calc.name().equals(name)) {
                    return calc.calculate(instrument, timeframe);
                }
            }
            return null;
        }

        @Override
        public List<Factor> getAllFactors(Instrument instrument, Timeframe timeframe) {
            List<Factor> factors = new ArrayList<>();
            for (FactorCalculator calc : calculators) {
                try {
                    Factor f = calc.calculate(instrument, timeframe);
                    if (f != null && f.isUsable()) {
                        factors.add(f);
                    }
                } catch (Exception e) {
                    // 忽略单个因子计算失败
                }
            }
            return factors;
        }
    }
}
