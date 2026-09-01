package com.tj.crypto.admin.application;

import com.tj.crypto.backtest.data.InMemoryHistoricalDataProvider;
import com.tj.crypto.backtest.engine.BacktestConfig;
import com.tj.crypto.backtest.engine.BacktestEngine;
import com.tj.crypto.backtest.engine.BacktestExecutionContext;
import com.tj.crypto.backtest.engine.BacktestResult;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.config.properties.MarketUniverseProperties;
import com.tj.crypto.factor.core.FactorRegistry;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.research.experiment.RegisteredBacktestExperimentService;
import com.tj.crypto.storage.service.AutoBackfillService;
import com.tj.crypto.storage.service.BarEventPersistenceService;
import com.tj.crypto.strategy.factor.CompositeFactorStrategy;
import com.tj.crypto.strategy.factor.FactorPositionMode;
import com.tj.crypto.strategy.factor.FactorStrategySpec;
import com.tj.crypto.strategy.core.Strategy;
import com.tj.crypto.strategy.core.StrategyManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Runs isolated admin-requested backtests against persisted canonical bars. */
@Service
public class BacktestApplicationService {
    private final BacktestEngine backtestEngine;
    private final BarEventPersistenceService barService;
    private final StrategyManager strategyManager;
    private final FactorRegistry factorRegistry;
    private final AutoBackfillService autoBackfillService;
    private final MarketUniverseProperties marketUniverse;
    private final RegisteredBacktestExperimentService registeredExperiments;

    @Autowired
    public BacktestApplicationService(BacktestEngine backtestEngine,
                                      BarEventPersistenceService barService,
                                      StrategyManager strategyManager,
                                      FactorRegistry factorRegistry,
                                      AutoBackfillService autoBackfillService,
                                      MarketUniverseProperties marketUniverse,
                                      RegisteredBacktestExperimentService registeredExperiments) {
        this.backtestEngine = backtestEngine;
        this.barService = barService;
        this.strategyManager = strategyManager;
        this.factorRegistry = factorRegistry;
        this.autoBackfillService = autoBackfillService;
        this.marketUniverse = marketUniverse;
        this.registeredExperiments = registeredExperiments;
    }

    /** Compatibility assembly for isolated unit tests; Spring always supplies the registry gate. */
    public BacktestApplicationService(BacktestEngine backtestEngine,
                                      BarEventPersistenceService barService,
                                      StrategyManager strategyManager,
                                      FactorRegistry factorRegistry,
                                      AutoBackfillService autoBackfillService,
                                      MarketUniverseProperties marketUniverse) {
        this(backtestEngine, barService, strategyManager, factorRegistry,
                autoBackfillService, marketUniverse, null);
    }

    public BacktestResult run(String strategyName, Exchange exchange,
                                           MarketType marketType, String symbol,
                                           String timeframeCode, int days,
                                           int warmupBars, BigDecimal initialBalance) {
        return run(strategyName, exchange, marketType, symbol, timeframeCode, days,
                warmupBars, initialBalance, false);
    }

    public BacktestResult run(String strategyName, Exchange exchange,
                              MarketType marketType, String symbol,
                              String timeframeCode, int days,
                              int warmupBars, BigDecimal initialBalance,
                              boolean autoBackfill) {
        return run(strategyName, exchange, marketType, symbol, timeframeCode,
                days, warmupBars, initialBalance, autoBackfill, "system");
    }

    public BacktestResult run(String strategyName, Exchange exchange,
                              MarketType marketType, String symbol,
                              String timeframeCode, int days,
                              int warmupBars, BigDecimal initialBalance,
                              boolean autoBackfill, String operator) {
        return withinExecutionContext(operator, () -> runStrategyInternal(strategyName, exchange,
                marketType, symbol, timeframeCode, days, warmupBars, initialBalance, autoBackfill));
    }

    private BacktestResult runStrategyInternal(String strategyName, Exchange exchange,
                                               MarketType marketType, String symbol,
                                               String timeframeCode, int days,
                                               int warmupBars, BigDecimal initialBalance,
                                               boolean autoBackfill) {
        if (days < 1 || days > 3650) throw new IllegalArgumentException("days must be between 1 and 3650");
        if (warmupBars < 0 || warmupBars > 5000) {
            throw new IllegalArgumentException("warmupBars must be between 0 and 5000");
        }
        marketUniverse.validate(exchange, marketType, symbol);
        symbol = MarketUniverseProperties.normalizeSymbol(symbol);
        Timeframe timeframe = Timeframe.fromCode(timeframeCode);
        int backfillDays = days + (int) Math.ceil(
                (double) warmupBars * timeframe.getMillis() / 86_400_000D);
        if (autoBackfill) {
            autoBackfillService.backfillIfNeeded(exchange, marketType,
                    symbol, timeframeCode, Math.max(1, backfillDays));
        }
        long end = (System.currentTimeMillis() / timeframe.getMillis()) * timeframe.getMillis()
                - timeframe.getMillis();
        long start = end - days * 86_400_000L;
        long dataStart = start - warmupBars * timeframe.getMillis();
        Instrument instrument = Instrument.of(exchange, marketType, symbol);
        List<BarEvent> bars = barService.loadByTimeRange(instrument, timeframe, dataStart, end);
        if (bars.size() < Math.max(2, warmupBars + 2)) {
            throw new IllegalArgumentException(
                    autoBackfill
                            ? "Insufficient persisted bars after backfill for requested backtest window"
                            : "Insufficient persisted bars for requested backtest window");
        }
        Strategy strategy = freshStrategy(strategyManager.getStrategy(strategyName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown strategy: " + strategyName)));
        BacktestConfig config = new BacktestConfig(
                instrument, timeframe, dataStart, start, end, initialBalance);
        return runRegistered(config, strategy.name(), Map.of(
                        "kind", "REGISTERED_STRATEGY",
                        "strategyName", strategy.name(),
                        "strategyClass", strategy.getClass().getName()), bars,
                () -> backtestEngine.run(config, strategy, new InMemoryHistoricalDataProvider(bars)));
    }

    public BacktestResult runFactorStrategy(
            Exchange exchange, MarketType marketType, String symbol,
            String timeframeCode, int days, int warmupBars,
            BigDecimal initialBalance, boolean autoBackfill,
            FactorStrategySpec spec) {
        return runFactorStrategy(exchange, marketType, symbol, timeframeCode, days,
                warmupBars, initialBalance, autoBackfill, spec, "system");
    }

    public BacktestResult runFactorStrategy(
            Exchange exchange, MarketType marketType, String symbol,
            String timeframeCode, int days, int warmupBars,
            BigDecimal initialBalance, boolean autoBackfill,
            FactorStrategySpec spec, String operator) {
        return withinExecutionContext(operator, () -> runFactorStrategyInternal(exchange,
                marketType, symbol, timeframeCode, days, warmupBars,
                initialBalance, autoBackfill, spec));
    }

    private BacktestResult runFactorStrategyInternal(
            Exchange exchange, MarketType marketType, String symbol,
            String timeframeCode, int days, int warmupBars,
            BigDecimal initialBalance, boolean autoBackfill,
            FactorStrategySpec spec) {
        validateFactorStrategy(marketType, spec);
        if (days < 1 || days > 3650) {
            throw new IllegalArgumentException("days must be between 1 and 3650");
        }
        if (warmupBars < 0 || warmupBars > 5000) {
            throw new IllegalArgumentException("warmupBars must be between 0 and 5000");
        }
        marketUniverse.validate(exchange, marketType, symbol);
        String normalizedSymbol = MarketUniverseProperties.normalizeSymbol(symbol);
        Timeframe timeframe = Timeframe.fromCode(timeframeCode);
        int backfillDays = days + (int) Math.ceil(
                (double) warmupBars * timeframe.getMillis() / 86_400_000D);
        if (autoBackfill) {
            autoBackfillService.backfillIfNeeded(exchange, marketType,
                    normalizedSymbol, timeframeCode, Math.max(1, backfillDays));
        }

        long end = (System.currentTimeMillis() / timeframe.getMillis()) * timeframe.getMillis()
                - timeframe.getMillis();
        long start = end - days * 86_400_000L;
        long dataStart = start - warmupBars * timeframe.getMillis();
        Instrument instrument = Instrument.of(exchange, marketType, normalizedSymbol);
        List<BarEvent> bars = barService.loadByTimeRange(
                instrument, timeframe, dataStart, end);
        if (bars.size() < Math.max(2, warmupBars + 2)) {
            throw new IllegalArgumentException(
                    "Insufficient persisted bars after backfill for requested factor backtest");
        }
        BacktestConfig config = new BacktestConfig(
                instrument, timeframe, dataStart, start, end, initialBalance);
        CompositeFactorStrategy strategy = new CompositeFactorStrategy(spec);
        return runRegistered(config, strategy.name(), spec, bars,
                () -> backtestEngine.run(config, strategy,
                        new InMemoryHistoricalDataProvider(bars)));
    }

    private BacktestResult runRegistered(BacktestConfig config, String experimentName,
                                         Object strategyDefinition, List<BarEvent> bars,
                                         Supplier<BacktestResult> execution) {
        if (registeredExperiments == null) return execution.get();
        RegisteredBacktestExperimentService.PreparedTrial prepared = registeredExperiments.prepare(
                BacktestExecutionContext.resultId(), experimentName, config, strategyDefinition,
                bars, BacktestExecutionContext.randomSeed(), BacktestExecutionContext.operator());
        BacktestExecutionContext.bindResearchLineage(prepared.lineage());
        try {
            BacktestResult result = execution.get();
            registeredExperiments.succeeded(prepared, result);
            return result;
        } catch (RuntimeException failure) {
            registeredExperiments.failed(prepared, failure);
            throw failure;
        }
    }

    private BacktestResult withinExecutionContext(String operator, Supplier<BacktestResult> execution) {
        if (BacktestExecutionContext.active()) return execution.get();
        return BacktestExecutionContext.run(UUID.randomUUID().toString(), 42L,
                com.tj.crypto.backtest.engine.BacktestProgressMonitor.NONE,
                operator, execution);
    }

    private void validateFactorStrategy(MarketType marketType, FactorStrategySpec spec) {
        if (marketType == MarketType.SPOT
                && spec.positionMode() == FactorPositionMode.LONG_SHORT) {
            throw new IllegalArgumentException("Spot factor backtests must use LONG_ONLY mode");
        }
        for (String factorName : spec.factorNames()) {
            factorRegistry.require(factorName);
            if (!factorRegistry.supportsBarHistory(factorName)) {
                throw new IllegalArgumentException(
                        "Factor " + factorName
                                + " requires non-candle event history and cannot be used in this backtest");
            }
        }
    }

    private Strategy freshStrategy(Strategy template) {
        try {
            var constructor = template.getClass().getDeclaredConstructor();
            constructor.setAccessible(true);
            return (Strategy) constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Strategy cannot be instantiated for an isolated backtest: " + template.name(), e);
        }
    }
}
