package com.tj.crypto.trading.venue;

/** Normalized venue state used at the anti-corruption boundary. */
public enum VenueOrderState {
    ACCEPTED,
    PARTIALLY_FILLED,
    FILLED,
    CANCEL_PENDING,
    CANCELLED,
    REJECTED,
    EXPIRED,
    UNKNOWN
}
