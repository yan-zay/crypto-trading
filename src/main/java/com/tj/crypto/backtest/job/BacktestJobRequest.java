package com.tj.crypto.backtest.job;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.strategy.factor.FactorStrategySpec;

import java.math.BigDecimal;

/** Serializable request envelope for both registered and composite-factor strategies. */
public record BacktestJobRequest(
        BacktestJobType type,
        String strategyName,
        Exchange exchange,
        MarketType marketType,
        String symbol,
        String timeframe,
        Integer days,
        Integer warmupBars,
        BigDecimal initialBalance,
        Boolean autoBackfill,
        FactorStrategySpec factorStrategy,
        Long randomSeed
) {
    public BacktestJobRequest {
        type = type == null ? BacktestJobType.STRATEGY : type;
        exchange = exchange == null ? Exchange.BINANCE : exchange;
        marketType = marketType == null ? MarketType.PERPETUAL : marketType;
        symbol = symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol;
        timeframe = timeframe == null || timeframe.isBlank() ? "1h" : timeframe;
        days = days == null ? 30 : days;
        warmupBars = warmupBars == null ? 200 : warmupBars;
        initialBalance = initialBalance == null ? BigDecimal.valueOf(10_000) : initialBalance;
        autoBackfill = autoBackfill == null || autoBackfill;
        randomSeed = randomSeed == null ? 42L : randomSeed;
        if (type == BacktestJobType.STRATEGY && (strategyName == null || strategyName.isBlank())) {
            throw new IllegalArgumentException("strategyName is required for STRATEGY jobs");
        }
        if (type == BacktestJobType.FACTOR && factorStrategy == null) {
            throw new IllegalArgumentException("factorStrategy is required for FACTOR jobs");
        }
    }
}
