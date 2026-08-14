package com.tj.crypto.trading.paper;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;

import java.math.BigDecimal;

/** Manual paper-only market snapshot, useful for deterministic testing and operator drills. */
public record PaperMarkRequest(
        Exchange exchange,
        MarketType marketType,
        String symbol,
        BigDecimal price,
        BigDecimal highPrice,
        BigDecimal lowPrice,
        BigDecimal baseVolume,
        long eventTimeMs
) {
    public PaperMarkRequest {
        if (exchange == null || marketType == null || symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("instrument identity is required");
        }
        if (price == null || price.signum() <= 0) throw new IllegalArgumentException("price must be positive");
        highPrice = highPrice == null ? price : highPrice;
        lowPrice = lowPrice == null ? price : lowPrice;
        baseVolume = baseVolume == null ? BigDecimal.ZERO : baseVolume;
        if (highPrice.compareTo(lowPrice) < 0 || price.compareTo(highPrice) > 0
                || price.compareTo(lowPrice) < 0) {
            throw new IllegalArgumentException("price must lie inside [lowPrice, highPrice]");
        }
        if (eventTimeMs <= 0) eventTimeMs = System.currentTimeMillis();
    }
}
