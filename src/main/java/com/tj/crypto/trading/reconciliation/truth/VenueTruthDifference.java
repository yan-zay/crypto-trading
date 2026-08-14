package com.tj.crypto.trading.reconciliation.truth;

import com.tj.crypto.common.domain.Exchange;

/** Structured, non-secret evidence explaining why live promotion remains blocked. */
public record VenueTruthDifference(Code code, Exchange exchange,
                                   VenueTruthCapability capability,
                                   String identity, String message) {
    public VenueTruthDifference {
        if (code == null) throw new IllegalArgumentException("difference code is required");
        identity = identity == null ? "" : identity;
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("difference message is required");
        }
    }

    public enum Code {
        INTERNAL_RECOVERY_NOT_READY,
        NO_CONFIGURED_GATEWAY,
        VENUE_CAPABILITY_UNSUPPORTED,
        INTERNAL_CAPABILITY_UNSUPPORTED,
        VENUE_QUERY_FAILED,
        INTERNAL_QUERY_FAILED,
        VENUE_ORPHAN_ORDER,
        INTERNAL_ORDER_MISSING_AT_VENUE,
        ORDER_MISMATCH,
        INTERNAL_FILL_MISSING,
        VENUE_FILL_MISSING,
        FILL_MISMATCH,
        INTERNAL_BALANCE_MISSING,
        VENUE_BALANCE_MISSING,
        BALANCE_MISMATCH,
        INTERNAL_POSITION_MISSING,
        VENUE_POSITION_MISSING,
        POSITION_MISMATCH,
        CONCURRENT_RUN_SKIPPED
    }
}
