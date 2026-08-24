package com.tj.crypto.autonomy.canary;

/** A bounded fraction of an independently approved parent capital budget. */
public record CapitalRung(String rungId, int ordinal, int maxApprovedCapitalBps) {
    public CapitalRung {
        rungId = CanaryCanonical.required(rungId, "rungId");
        if (ordinal < 1) throw new IllegalArgumentException("ordinal must be positive");
        if (maxApprovedCapitalBps < 1 || maxApprovedCapitalBps > 100) {
            throw new IllegalArgumentException("canary capital must remain between 1 and 100 bps");
        }
    }
}
