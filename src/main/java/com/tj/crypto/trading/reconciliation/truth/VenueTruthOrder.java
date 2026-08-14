package com.tj.crypto.trading.reconciliation.truth;

import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.trading.venue.VenueOrderState;

import java.math.BigDecimal;
import java.util.Locale;

/** Canonical account-wide active order fact from either the venue or internal projection. */
public record VenueTruthOrder(String venueOrderId, String clientOrderId, MarketType marketType,
                              String symbol, VenueOrderState state, BigDecimal originalQuantity,
                              BigDecimal cumulativeFilledQuantity, long eventTimeMs) {
    public VenueTruthOrder {
        venueOrderId = trimToNull(venueOrderId);
        clientOrderId = trimToNull(clientOrderId);
        if (venueOrderId == null && clientOrderId == null) {
            throw new IllegalArgumentException("A truth order requires venueOrderId or clientOrderId");
        }
        if (marketType == null) throw new IllegalArgumentException("marketType is required");
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("symbol is required");
        symbol = canonicalSymbol(symbol);
        if (state == null) throw new IllegalArgumentException("order state is required");
        originalQuantity = nonNegative(originalQuantity, "originalQuantity");
        cumulativeFilledQuantity = nonNegative(cumulativeFilledQuantity, "cumulativeFilledQuantity");
        if (cumulativeFilledQuantity.compareTo(originalQuantity) > 0) {
            throw new IllegalArgumentException("filled quantity exceeds original quantity");
        }
        if (eventTimeMs <= 0) throw new IllegalArgumentException("eventTimeMs must be positive");
    }

    public String identity() {
        return venueOrderId != null ? "venue:" + venueOrderId : "client:" + clientOrderId;
    }

    static String canonicalSymbol(String value) {
        return value.replace("-SWAP", "").replace("-", "")
                .trim().toUpperCase(Locale.ROOT);
    }

    private static BigDecimal nonNegative(BigDecimal value, String field) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return value.stripTrailingZeros();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
