package com.tj.crypto.backtest.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.tj.crypto.backtest.data.HistoricalDataProvider;
import com.tj.crypto.backtest.portfolio.FeeModel;
import com.tj.crypto.backtest.portfolio.VirtualAccount;
import com.tj.crypto.backtest.report.PerformanceCalculator;
import com.tj.crypto.backtest.report.PerformanceReport;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.event.InMemoryEventBus;
import com.tj.crypto.execution.ExecutionEngine;
import com.tj.crypto.factor.cache.BarCache;
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
 * - 使用 FillModel 决定成交价格（防未来函数）
 * - 支持手续费模型（可选）
 * - 回测报告包含假设快照
 */
@Slf4j
@Component
public class BacktestEngine {

    private final PerformanceCalculator performanceCalculator;
    private final List<FactorCalculator> factorCalculators;
    private final ExecutionEngine executionEngine;
    private final FeeModel feeModel;
    private final BacktestAssumptions assumptions;
    private final FillModel fillModel;
    private final ObjectMapper objectMapper;

    public BacktestEngine(PerformanceCalculator performanceCalculator,
                          List<FactorCalculator> factorCalculators,
                          ExecutionEngine executionEngine,
                          FeeModel feeModel,
                          BacktestAssumptions assumptions) {
        this.performanceCalculator = performanceCalculator;
        this.factorCalculators = factorCalculators;
        this.executionEngine = executionEngine;
        this.feeModel = feeModel;
        this.assumptions = assumptions != null ? assumptions : BacktestAssumptions.defaults();
        this.fillModel = FillModel.create(this.assumptions.fillMode());
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public BacktestEngine(PerformanceCalculator performanceCalculator,
                          List<FactorCalculator> factorCalculators,
                          ExecutionEngine executionEngine,
                          FeeModel feeModel) {
        this(performanceCalculator, factorCalculators, executionEngine, feeModel, null);
    }

    /**
     * 便捷构造函数（无手续费模型，使用默认假设）。
     */
    public BacktestEngine(PerformanceCalculator performanceCalculator,
                          List<FactorCalculator> factorCalculators,
                          ExecutionEngine executionEngine) {
        this(performanceCalculator, factorCalculators, executionEngine, null, null);
    }

    /**
     * 运行回测（使用独立的内部 BarCache）。
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

        // 3. 创建虚拟账户（带手续费模型）
        VirtualAccount account = new VirtualAccount(config.initialBalance(), feeModel);

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
                // 使用 FillModel 计算基础价格，ExecutionEngine 会应用滑点
                BigDecimal basePrice = fillModel.calculateBasePrice(bar);
                executionEngine.execute(signal, account, basePrice, bar.metadata().exchangeTimestamp());
            }
        });

        // 7. 回放历史事件
        EventReplayer replayer = new EventReplayer(backtestEventBus);
        int eventCount = replayer.replay(dataProvider, config.instrument(),
                config.timeframe(), config.startTime(), config.endTime());

        // 8. 使用最后一根 bar 的收盘价平仓（而非 0）
        closeAllPositions(account, config.instrument(), lastClosePrice.get(), config.endTime());

        // 9. 序列化假设为 JSON
        String assumptionsJson = serializeAssumptions();

        // 10. 计算性能报告（含假设快照）
        BigDecimal finalBalance = account.getBalance();
        PerformanceReport report = performanceCalculator.calculate(
                account.getTrades(), config.initialBalance(), finalBalance,
                config.startTime(), config.endTime(), assumptionsJson);

        log.info("Backtest complete: {} events, {} signals, {} trades, totalFees=${}, fillMode={}, {}",
                eventCount, signals.size(), account.getTrades().size(),
                account.getTotalFeesPaid(), assumptions.fillMode(), report);

        return new BacktestResult(config, signals, account.getTrades(), report, finalBalance, assumptions);
    }

    private void closeAllPositions(VirtualAccount account, Instrument instrument,
                                   BigDecimal lastPrice, long timestamp) {
        if (account.hasPosition(instrument)) {
            account.closePosition(instrument, lastPrice, timestamp);
        }
    }

    /**
     * 运行回测（使用外部 BarCache）。
     * 当 FactorCalculator 需要共享同一 BarCache 时使用此方法。
     * 外部 BarCache 会自动订阅回测事件总线，确保因子能读到回放的 bar。
     */
    public BacktestResult run(BacktestConfig config, Strategy strategy,
                              HistoricalDataProvider dataProvider, BarCache backtestBarCache) {
        log.info("Starting backtest with external BarCache: {} {} [{}, {}] initialBalance=${}",
                config.instrument().symbol(), config.timeframe().getCode(),
                config.startTime(), config.endTime(), config.initialBalance());

        // 1. 创建独立的事件总线
        InMemoryEventBus backtestEventBus = new InMemoryEventBus();

        // 2. 外部 BarCache 订阅回测事件总线（构造函数中完成）
        InMemoryBarCache barCache = (backtestBarCache instanceof InMemoryBarCache)
                ? (InMemoryBarCache) backtestBarCache
                : new InMemoryBarCache(backtestEventBus);

        // 3. 创建回测专用 StrategyContext（带因子计算）
        StrategyContext backtestContext = new BacktestStrategyContext(barCache, factorCalculators);

        // 4. 创建虚拟账户（带手续费模型）
        VirtualAccount account = new VirtualAccount(config.initialBalance(), feeModel);

        // 5. 收集信号
        List<SignalEvent> signals = new ArrayList<>();

        // 6. 跟踪最后一根 bar 的收盘价
        AtomicReference<BigDecimal> lastClosePrice = new AtomicReference<>(BigDecimal.ZERO);

        // 7. 订阅 BarEvent
        backtestEventBus.subscribe(BarEvent.class, bar -> {
            barCache.addBar(bar);
            lastClosePrice.set(bar.close());

            if (!bar.closed()) return;

            SignalEvent signal = strategy.onEvent(bar, backtestContext);
            if (signal != null) {
                signals.add(signal);
                // 使用 FillModel 计算基础价格，ExecutionEngine 会应用滑点
                BigDecimal basePrice = fillModel.calculateBasePrice(bar);
                executionEngine.execute(signal, account, basePrice, bar.metadata().exchangeTimestamp());
            }
        });

        // 8. 回放历史事件
        EventReplayer replayer = new EventReplayer(backtestEventBus);
        int eventCount = replayer.replay(dataProvider, config.instrument(),
                config.timeframe(), config.startTime(), config.endTime());

        // 9. 使用最后一根 bar 的收盘价平仓
        closeAllPositions(account, config.instrument(), lastClosePrice.get(), config.endTime());

        // 10. 序列化假设为 JSON
        String assumptionsJson = serializeAssumptions();

        // 11. 计算性能报告（含假设快照）
        BigDecimal finalBalance = account.getBalance();
        PerformanceReport report = performanceCalculator.calculate(
                account.getTrades(), config.initialBalance(), finalBalance,
                config.startTime(), config.endTime(), assumptionsJson);

        log.info("Backtest complete: {} events, {} signals, {} trades, totalFees=${}, fillMode={}, {}",
                eventCount, signals.size(), account.getTrades().size(),
                account.getTotalFeesPaid(), assumptions.fillMode(), report);

        return new BacktestResult(config, signals, account.getTrades(), report, finalBalance, assumptions);
    }

    /**
     * 序列化假设为 JSON 字符串。
     */
    private String serializeAssumptions() {
        try {
            return objectMapper.writeValueAsString(assumptions);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize assumptions to JSON", e);
            return "{}";
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
