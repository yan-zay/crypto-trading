package com.tj.crypto.backtest.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.tj.crypto.backtest.data.HistoricalDataProvider;
import com.tj.crypto.backtest.portfolio.FeeModel;
import com.tj.crypto.backtest.portfolio.VirtualAccount;
import com.tj.crypto.backtest.portfolio.FuturesAccount;
import com.tj.crypto.backtest.portfolio.TradingAccount;
import com.tj.crypto.backtest.report.PerformanceCalculator;
import com.tj.crypto.backtest.report.EquityPoint;
import com.tj.crypto.backtest.report.PerformanceReport;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.event.InMemoryEventBus;
import com.tj.crypto.execution.ExecutionEngine;
import com.tj.crypto.factor.cache.BarCache;
import com.tj.crypto.factor.cache.InMemoryBarCache;
import com.tj.crypto.factor.core.Factor;
import com.tj.crypto.factor.core.BarHistoryFactorCalculator;
import com.tj.crypto.factor.core.FactorCalculator;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.strategy.core.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private final List<BacktestResultListener> resultListeners;

    @Autowired
    public BacktestEngine(PerformanceCalculator performanceCalculator,
                          List<FactorCalculator> factorCalculators,
                          ExecutionEngine executionEngine,
                          FeeModel feeModel,
                          @Nullable BacktestAssumptions assumptions,
                          List<BacktestResultListener> resultListeners) {
        this.performanceCalculator = performanceCalculator;
        this.factorCalculators = factorCalculators;
        this.executionEngine = executionEngine;
        this.feeModel = feeModel;
        this.assumptions = assumptions != null ? assumptions : BacktestAssumptions.defaults();
        this.fillModel = FillModel.create(this.assumptions.fillMode());
        this.resultListeners = List.copyOf(resultListeners);
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public BacktestEngine(PerformanceCalculator performanceCalculator,
                          List<FactorCalculator> factorCalculators,
                          ExecutionEngine executionEngine,
                          FeeModel feeModel,
                          @Nullable BacktestAssumptions assumptions) {
        this(performanceCalculator, factorCalculators, executionEngine,
                feeModel, assumptions, List.of());
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
        ExecutionEngine runExecutionEngine = executionEngine.withoutJournals();
        InMemoryEventBus backtestEventBus = new InMemoryEventBus();
        InMemoryBarCache backtestBarCache = new InMemoryBarCache(backtestEventBus);

        // 2. 创建回测专用 StrategyContext（带因子计算）
        StrategyContext backtestContext = new BacktestStrategyContext(backtestBarCache, factorCalculators);

        // 3. 创建虚拟账户（带手续费模型）
        TradingAccount account = createAccount(config);

        // 4. 收集信号
        List<SignalEvent> signals = new ArrayList<>();
        List<EquityPoint> equityCurve = new ArrayList<>();
        equityCurve.add(new EquityPoint(config.startTime(), config.initialBalance()));

        // 5. 跟踪最后一根 bar 的收盘价
        AtomicReference<BigDecimal> lastClosePrice = new AtomicReference<>(BigDecimal.ZERO);
        AtomicReference<SignalEvent> pendingSignal = new AtomicReference<>();

        // 6. 订阅 BarEvent
        backtestEventBus.subscribe(BarEvent.class, bar -> {
            if (!bar.closed()) return;

            long barTime = bar.metadata().exchangeTimestamp();
            SignalEvent toExecute = pendingSignal.getAndSet(null);
            if (toExecute != null && barTime >= config.startTime()) {
                runExecutionEngine.execute(toExecute, account,
                        fillModel.calculateBasePrice(bar), barTime, bar.volume());
            }
            processLiquidation(account, bar);

            backtestBarCache.addBar(bar);
            lastClosePrice.set(bar.close());
            SignalEvent signal = strategy.onEvent(bar, backtestContext);
            if (barTime < config.startTime()) return;

            equityCurve.add(new EquityPoint(barTime,
                    account.riskSnapshot(config.instrument(), bar.close()).equity()));

            if (signal != null) {
                signals.add(signal);
                if (signal.type() != SignalType.HOLD) {
                    pendingSignal.set(signal);
                }
            }
        });

        // 7. 回放历史事件
        EventReplayer replayer = new EventReplayer(backtestEventBus);
        int eventCount = replayer.replay(dataProvider, config.instrument(),
                config.timeframe(), config.dataStartTime(), config.endTime());

        // 8. 使用最后一根 bar 的收盘价平仓（而非 0）
        closeAllPositions(account, config.instrument(), lastClosePrice.get(), config.endTime());
        equityCurve.add(new EquityPoint(config.endTime(), account.getBalance()));

        // 9. 序列化假设为 JSON
        String assumptionsJson = serializeAssumptions();

        // 10. 计算性能报告（含假设快照）
        BigDecimal finalBalance = account.getBalance();
        PerformanceReport report = performanceCalculator.calculate(
                account.getTrades(), config.initialBalance(), finalBalance,
                config.startTime(), config.endTime(), assumptionsJson, equityCurve);

        log.info("Backtest complete: {} events, {} signals, {} trades, totalFees=${}, fillMode={}, {}",
                eventCount, signals.size(), account.getTrades().size(),
                account.getTotalFeesPaid(), assumptions.fillMode(), report);

        BacktestResult result = new BacktestResult(
                config, signals, account.getTrades(), report, finalBalance,
                assumptions, equityCurve, strategy.name(), serializeStrategy(strategy));
        BacktestExecutionContext.monitor().checkpoint();
        notifyCompleted(result);
        return result;
    }

    private void closeAllPositions(TradingAccount account, Instrument instrument,
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

        // 1. 创建独立的事件总线和风控会话
        ExecutionEngine runExecutionEngine = executionEngine.withoutJournals();
        InMemoryEventBus backtestEventBus = new InMemoryEventBus();

        // 2. 外部 BarCache 订阅回测事件总线（构造函数中完成）
        InMemoryBarCache barCache = (backtestBarCache instanceof InMemoryBarCache)
                ? (InMemoryBarCache) backtestBarCache
                : new InMemoryBarCache(backtestEventBus);

        // 3. 创建回测专用 StrategyContext（带因子计算）
        StrategyContext backtestContext = new BacktestStrategyContext(barCache, factorCalculators);

        // 4. 创建虚拟账户（带手续费模型）
        TradingAccount account = createAccount(config);

        // 5. 收集信号
        List<SignalEvent> signals = new ArrayList<>();
        List<EquityPoint> equityCurve = new ArrayList<>();
        equityCurve.add(new EquityPoint(config.startTime(), config.initialBalance()));

        // 6. 跟踪最后一根 bar 的收盘价
        AtomicReference<BigDecimal> lastClosePrice = new AtomicReference<>(BigDecimal.ZERO);
        AtomicReference<SignalEvent> pendingSignal = new AtomicReference<>();

        // 7. 订阅 BarEvent
        backtestEventBus.subscribe(BarEvent.class, bar -> {
            if (!bar.closed()) return;

            long barTime = bar.metadata().exchangeTimestamp();
            SignalEvent toExecute = pendingSignal.getAndSet(null);
            if (toExecute != null && barTime >= config.startTime()) {
                runExecutionEngine.execute(toExecute, account,
                        fillModel.calculateBasePrice(bar), barTime, bar.volume());
            }
            processLiquidation(account, bar);

            barCache.addBar(bar);
            lastClosePrice.set(bar.close());
            SignalEvent signal = strategy.onEvent(bar, backtestContext);
            if (barTime < config.startTime()) return;

            equityCurve.add(new EquityPoint(barTime,
                    account.riskSnapshot(config.instrument(), bar.close()).equity()));

            if (signal != null) {
                signals.add(signal);
                if (signal.type() != SignalType.HOLD) {
                    pendingSignal.set(signal);
                }
            }
        });

        // 8. 回放历史事件
        EventReplayer replayer = new EventReplayer(backtestEventBus);
        int eventCount = replayer.replay(dataProvider, config.instrument(),
                config.timeframe(), config.dataStartTime(), config.endTime());

        // 9. 使用最后一根 bar 的收盘价平仓
        closeAllPositions(account, config.instrument(), lastClosePrice.get(), config.endTime());
        equityCurve.add(new EquityPoint(config.endTime(), account.getBalance()));

        // 10. 序列化假设为 JSON
        String assumptionsJson = serializeAssumptions();

        // 11. 计算性能报告（含假设快照）
        BigDecimal finalBalance = account.getBalance();
        PerformanceReport report = performanceCalculator.calculate(
                account.getTrades(), config.initialBalance(), finalBalance,
                config.startTime(), config.endTime(), assumptionsJson, equityCurve);

        log.info("Backtest complete: {} events, {} signals, {} trades, totalFees=${}, fillMode={}, {}",
                eventCount, signals.size(), account.getTrades().size(),
                account.getTotalFeesPaid(), assumptions.fillMode(), report);

        BacktestResult result = new BacktestResult(
                config, signals, account.getTrades(), report, finalBalance,
                assumptions, equityCurve, strategy.name(), serializeStrategy(strategy));
        BacktestExecutionContext.monitor().checkpoint();
        notifyCompleted(result);
        return result;
    }

    private void notifyCompleted(BacktestResult result) {
        for (BacktestResultListener listener : resultListeners) {
            try {
                listener.onCompleted(result);
            } catch (RuntimeException e) {
                log.error("Backtest result listener failed for {} {}",
                        result.config().instrument().id().value(),
                        result.config().timeframe().getCode(), e);
                if (listener.requiredForCompletion()) throw e;
            }
        }
    }

    private TradingAccount createAccount(BacktestConfig config) {
        return switch (config.instrument().marketType()) {
            case SPOT -> new VirtualAccount(config.initialBalance(), feeModel);
            case FUTURES, PERPETUAL -> new FuturesAccount(config.initialBalance(), feeModel);
        };
    }

    private void processLiquidation(TradingAccount account, BarEvent bar) {
        if (!(account instanceof FuturesAccount futuresAccount)
                || !futuresAccount.hasPosition(bar.instrument())) {
            return;
        }
        BigDecimal liquidationPrice = futuresAccount.getLiquidationPrice(bar.instrument());
        if (liquidationPrice == null) return;
        com.tj.crypto.common.domain.OrderSide side = futuresAccount.getPositionSide(bar.instrument());
        boolean touched = side == com.tj.crypto.common.domain.OrderSide.LONG
                ? bar.low().compareTo(liquidationPrice) <= 0
                : bar.high().compareTo(liquidationPrice) >= 0;
        if (touched) {
            futuresAccount.liquidatePosition(
                    bar.instrument(), liquidationPrice, bar.metadata().exchangeTimestamp());
        }
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

    private String serializeStrategy(Strategy strategy) {
        try {
            return objectMapper.writeValueAsString(strategy.configuration());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Cannot serialize strategy configuration for " + strategy.name(), e);
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
                    if (!(calc instanceof BarHistoryFactorCalculator)) return null;
                    return calc.calculate(instrument, timeframe,
                            barCache.getBars(instrument, timeframe, 500));
                }
            }
            return null;
        }

        @Override
        public List<Factor> getAllFactors(Instrument instrument, Timeframe timeframe) {
            List<Factor> factors = new ArrayList<>();
            for (FactorCalculator calc : calculators) {
                if (!(calc instanceof BarHistoryFactorCalculator)) continue;
                try {
                    Factor f = calc.calculate(instrument, timeframe,
                            barCache.getBars(instrument, timeframe, 500));
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
