package com.tj.crypto.trading.instrument;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;

import java.math.BigDecimal;

/** Mutable-at-source exchange rule observation before it becomes a versioned database fact. */
public record InstrumentRuleSnapshot(
        Exchange exchange,
        MarketType marketType,
        String symbol,
        String venueSymbol,
        String baseAsset,
        String quoteAsset,
        String settleAsset,
        String status,
        BigDecimal tickSize,
        BigDecimal stepSize,
        BigDecimal minQuantity,
        BigDecimal maxQuantity,
        BigDecimal minNotional,
        BigDecimal contractMultiplier,
        String sourceVersion
) {}
