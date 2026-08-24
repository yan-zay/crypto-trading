package com.tj.crypto.autonomy.capital;

import java.time.Instant;
import java.util.Objects;

/** One exact scope's limits and independent expiry. */
public record ScopedRiskBudget(RiskScope scope, RiskLimits limits, Instant expiresAt)
        implements Comparable<ScopedRiskBudget> {
    public ScopedRiskBudget {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public boolean currentAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return expiresAt.isAfter(instant);
    }

    @Override
    public int compareTo(ScopedRiskBudget other) {
        return scope.compareTo(other.scope);
    }
}
