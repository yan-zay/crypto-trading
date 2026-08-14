package com.tj.crypto.trading.venue;

import com.tj.crypto.common.domain.Instrument;

/** A cancel command can address an order by venue or client identifier. */
public record VenueCancelCommand(
        Instrument instrument,
        String venueOrderId,
        String clientOrderId
) {
    public VenueCancelCommand {
        if (instrument == null) throw new IllegalArgumentException("instrument is required");
        if ((venueOrderId == null || venueOrderId.isBlank())
                && (clientOrderId == null || clientOrderId.isBlank())) {
            throw new IllegalArgumentException("venueOrderId or clientOrderId is required");
        }
    }
}
