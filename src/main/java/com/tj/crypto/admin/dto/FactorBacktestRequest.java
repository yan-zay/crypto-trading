package com.tj.crypto.admin.dto;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.strategy.factor.FactorStrategySpec;

import java.math.BigDecimal;

/** Request for a configurable single-factor or multi-factor historical backtest. */
public record FactorBacktestRequest(
        Exchange exchange,
        MarketType marketType,
        String symbol,
        String timeframe,
        Integer days,
        Integer warmupBars,
        BigDecimal initialBalance,
        Boolean autoBackfill,
        FactorStrategySpec strategy
) {
    public FactorBacktestRequest {
        exchange = exchange == null ? Exchange.BINANCE : exchange;
        marketType = marketType == null ? MarketType.PERPETUAL : marketType;
        symbol = symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol;
        timeframe = timeframe == null || timeframe.isBlank() ? "1h" : timeframe;
        days = days == null ? 30 : days;
        warmupBars = warmupBars == null ? 200 : warmupBars;
        initialBalance = initialBalance == null ? BigDecimal.valueOf(10_000) : initialBalance;
        autoBackfill = autoBackfill == null || autoBackfill;
        if (strategy == null) throw new IllegalArgumentException("strategy is required");
    }
}
