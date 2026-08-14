package com.tj.crypto.admin.application;

import com.tj.crypto.backtest.data.InMemoryHistoricalDataProvider;
import com.tj.crypto.backtest.engine.BacktestConfig;
import com.tj.crypto.backtest.engine.BacktestEngine;
import com.tj.crypto.backtest.engine.BacktestResult;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.config.properties.MarketUniverseProperties;
import com.tj.crypto.factor.core.FactorRegistry;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.storage.service.AutoBackfillService;
import com.tj.crypto.storage.service.BarEventPersistenceService;
import com.tj.crypto.strategy.factor.CompositeFactorStrategy;
import com.tj.crypto.strategy.factor.FactorPositionMode;
import com.tj.crypto.strategy.factor.FactorStrategySpec;
import com.tj.crypto.strategy.core.Strategy;
import com.tj.crypto.strategy.core.StrategyManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/** Runs isolated admin-requested backtests against persisted canonical bars. */
@Service
@RequiredArgsConstructor
public class BacktestApplicationService {
    private final BacktestEngine backtestEngine;
    private final BarEventPersistenceService barService;
    private final StrategyManager strategyManager;
    private final FactorRegistry factorRegistry;
    private final AutoBackfillService autoBackfillService;
    private final MarketUniverseProperties marketUniverse;

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
        return backtestEngine.run(config, strategy, new InMemoryHistoricalDataProvider(bars));
    }

    public BacktestResult runFactorStrategy(
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
        return backtestEngine.run(config, new CompositeFactorStrategy(spec),
                new InMemoryHistoricalDataProvider(bars));
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
