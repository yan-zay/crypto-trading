package com.tj.crypto.autonomy.operations;

import java.util.Locale;
import java.util.Optional;

/** Authoritative fact streams consumed by the independent operations watchdog. */
public enum OperationsObservationKind {
    MARKET_DATA,
    OMS,
    VENUE_TRUTH,
    WRITER_LEASE,
    KILL_SWITCH,
    GOVERNANCE_EVIDENCE,
    AGENT_HEARTBEAT;

    public static Optional<OperationsObservationKind> parse(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
