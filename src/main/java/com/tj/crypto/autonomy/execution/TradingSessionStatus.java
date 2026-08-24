package com.tj.crypto.autonomy.execution;

/** A closed or unknown session is never executable. */
public enum TradingSessionStatus {
    OPEN,
    CLOSED,
    UNKNOWN
}
