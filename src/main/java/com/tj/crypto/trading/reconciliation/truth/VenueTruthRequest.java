package com.tj.crypto.trading.reconciliation.truth;

/** Bounded account-wide discovery window. Adapters must paginate until the window is complete. */
public record VenueTruthRequest(long sinceMs, int limit) {
    public VenueTruthRequest {
        if (sinceMs < 0) throw new IllegalArgumentException("sinceMs must not be negative");
        if (limit < 1 || limit > 10_000) {
            throw new IllegalArgumentException("truth discovery limit must be between 1 and 10000");
        }
    }
}
