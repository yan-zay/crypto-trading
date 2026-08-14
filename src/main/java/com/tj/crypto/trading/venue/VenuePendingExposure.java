package com.tj.crypto.trading.venue;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;

import java.math.BigDecimal;

/** Gross exposure reserved by a durable live OMS order which is not terminal yet. */
public record VenuePendingExposure(
        Exchange exchange,
        MarketType marketType,
        String symbol,
        BigDecimal remainingQuantity,
        BigDecimal referencePrice,
        boolean reduceOnly
) {}
