package com.tj.crypto.trading.reconciliation.truth;

import com.tj.crypto.common.domain.MarketType;

import java.math.BigDecimal;

/** Canonical recent trade/fill fact; venue trade id is the cross-system idempotency key. */
public record VenueTruthFill(String exchangeTradeId, String venueOrderId, String clientOrderId,
                             MarketType marketType, String symbol, BigDecimal quantity,
                             BigDecimal price, long eventTimeMs) {
    public VenueTruthFill {
        if (exchangeTradeId == null || exchangeTradeId.isBlank()) {
            throw new IllegalArgumentException("exchangeTradeId is required");
        }
        exchangeTradeId = exchangeTradeId.trim();
        venueOrderId = trimToNull(venueOrderId);
        clientOrderId = trimToNull(clientOrderId);
        if (marketType == null) throw new IllegalArgumentException("marketType is required");
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("symbol is required");
        symbol = VenueTruthOrder.canonicalSymbol(symbol);
        quantity = positive(quantity, "quantity");
        price = positive(price, "price");
        if (eventTimeMs <= 0) throw new IllegalArgumentException("eventTimeMs must be positive");
    }

    private static BigDecimal positive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value.stripTrailingZeros();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
