package com.tj.crypto.trading.venue.stream;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.trading.venue.VenueOrderState;

import java.math.BigDecimal;

/** Normalized private order update preserving cumulative and incremental fill facts. */
public record VenueOrderUpdate(
        Exchange exchange,
        String externalEventId,
        String payloadChecksum,
        long eventTimeMs,
        Instrument instrument,
        String venueOrderId,
        String clientOrderId,
        String exchangeTradeId,
        String rawStatus,
        VenueOrderState state,
        BigDecimal originalQuantity,
        BigDecimal cumulativeFilledQuantity,
        BigDecimal lastFillQuantity,
        BigDecimal lastFillPrice,
        BigDecimal averageFillPrice,
        BigDecimal fee,
        String feeCurrency,
        String liquidityRole
) implements VenuePrivateEvent {
    public VenueOrderUpdate {
        cumulativeFilledQuantity = zero(cumulativeFilledQuantity);
        lastFillQuantity = zero(lastFillQuantity);
        fee = zero(fee);
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
