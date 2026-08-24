package com.tj.crypto.trading.venue.contract;

/** Mandatory evidence dimensions for an owner-controlled demo/testnet venue certification. */
public enum VenueContractScenario {
    PROFILE_BOUNDARY,
    ACCOUNT_AND_PERMISSION_BOUNDARY,
    PLACE_QUERY_CANCEL,
    PARTIAL_FILL,
    PRECISION_AND_MIN_NOTIONAL,
    RATE_LIMIT,
    ERROR_MAPPING,
    CLOCK_SKEW,
    PRIVATE_STREAM_RECONNECT,
    DUPLICATE_AND_DELAYED_EVENTS,
    UNKNOWN_QUERY_ONLY,
    ACTIVE_ORDERS,
    RECENT_FILLS,
    BALANCES,
    POSITIONS,
    PRIVATE_STREAM_SEVEN_DAY_STABILITY
}
