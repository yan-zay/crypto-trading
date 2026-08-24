package com.tj.crypto.autonomy.capital;

import java.util.List;
import java.util.Objects;

/** Versioned hard ceilings. Zero is valid and means no new risk in that dimension. */
public record RiskLimits(
        Money maxGrossNotional,
        Money maxNetNotional,
        Money maxSingleOrderNotional,
        Money maxDailyLoss,
        Money maxLossAtStop
) {
    public RiskLimits {
        List<Money> values = List.of(
                Objects.requireNonNull(maxGrossNotional, "maxGrossNotional"),
                Objects.requireNonNull(maxNetNotional, "maxNetNotional"),
                Objects.requireNonNull(maxSingleOrderNotional, "maxSingleOrderNotional"),
                Objects.requireNonNull(maxDailyLoss, "maxDailyLoss"),
                Objects.requireNonNull(maxLossAtStop, "maxLossAtStop"));
        AssetUnit unit = values.get(0).unit();
        for (Money value : values) {
            if (!value.unit().equals(unit)) {
                throw new IllegalArgumentException("all risk limits must use one valuation unit");
            }
            if (!value.isNonNegative()) {
                throw new IllegalArgumentException("risk limits must not be negative");
            }
        }
        if (maxSingleOrderNotional.compareTo(maxGrossNotional) > 0) {
            throw new IllegalArgumentException("maxSingleOrderNotional cannot exceed maxGrossNotional");
        }
        if (maxNetNotional.compareTo(maxGrossNotional) > 0) {
            throw new IllegalArgumentException("maxNetNotional cannot exceed maxGrossNotional");
        }
        if (maxLossAtStop.compareTo(maxDailyLoss) > 0) {
            throw new IllegalArgumentException("maxLossAtStop cannot exceed maxDailyLoss");
        }
    }

    public AssetUnit valuationUnit() {
        return maxGrossNotional.unit();
    }

    public boolean noGreaterThan(RiskLimits other) {
        Objects.requireNonNull(other, "other");
        return maxGrossNotional.compareTo(other.maxGrossNotional) <= 0
                && maxNetNotional.compareTo(other.maxNetNotional) <= 0
                && maxSingleOrderNotional.compareTo(other.maxSingleOrderNotional) <= 0
                && maxDailyLoss.compareTo(other.maxDailyLoss) <= 0
                && maxLossAtStop.compareTo(other.maxLossAtStop) <= 0;
    }

    public boolean strictlyLessThan(RiskLimits other) {
        return noGreaterThan(other) && !equals(other);
    }
}
