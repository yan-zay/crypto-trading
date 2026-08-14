package com.tj.crypto.trading.venue;

import java.math.BigDecimal;

/** Latest venue view. Status remains raw so reconciliation can preserve source truth. */
public record VenueOrderSnapshot(
        String venueOrderId,
        String clientOrderId,
        String rawStatus,
        VenueOrderState state,
        BigDecimal originalQuantity,
        BigDecimal cumulativeFilledQuantity,
        BigDecimal averageFillPrice,
        long eventTimeMs,
        boolean finalState
) {
    public VenueOrderSnapshot {
        originalQuantity = originalQuantity == null ? BigDecimal.ZERO : originalQuantity;
        cumulativeFilledQuantity = cumulativeFilledQuantity == null ? BigDecimal.ZERO : cumulativeFilledQuantity;
    }
}
