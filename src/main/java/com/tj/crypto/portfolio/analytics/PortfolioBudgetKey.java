package com.tj.crypto.portfolio.analytics;

import com.tj.crypto.common.domain.InstrumentId;

import java.util.Objects;
import java.util.Comparator;

/** Canonical account-scoped key for one portfolio budget dimension. */
public record PortfolioBudgetKey(
        BudgetDimension dimension,
        String accountId,
        String strategyId,
        InstrumentId instrumentId,
        ExposureDirection direction
) implements Comparable<PortfolioBudgetKey> {
    public PortfolioBudgetKey {
        Objects.requireNonNull(dimension, "dimension");
        accountId = required(accountId, "accountId");
        switch (dimension) {
            case ACCOUNT -> requireOnly(strategyId, instrumentId, direction, false, false, false);
            case STRATEGY -> {
                strategyId = required(strategyId, "strategyId");
                requireOnly(null, instrumentId, direction, true, false, false);
            }
            case INSTRUMENT -> {
                Objects.requireNonNull(instrumentId, "instrumentId");
                requireOnly(strategyId, null, direction, false, true, false);
            }
            case DIRECTION -> {
                Objects.requireNonNull(direction, "direction");
                requireOnly(strategyId, instrumentId, null, false, false, true);
            }
        }
    }

    public static PortfolioBudgetKey account(String accountId) {
        return new PortfolioBudgetKey(BudgetDimension.ACCOUNT, accountId, null, null, null);
    }

    public static PortfolioBudgetKey strategy(String accountId, String strategyId) {
        return new PortfolioBudgetKey(BudgetDimension.STRATEGY, accountId, strategyId, null, null);
    }

    public static PortfolioBudgetKey instrument(String accountId, InstrumentId instrumentId) {
        return new PortfolioBudgetKey(BudgetDimension.INSTRUMENT, accountId, null, instrumentId, null);
    }

    public static PortfolioBudgetKey direction(String accountId, ExposureDirection direction) {
        return new PortfolioBudgetKey(BudgetDimension.DIRECTION, accountId, null, null, direction);
    }

    public String canonicalValue() {
        return switch (dimension) {
            case ACCOUNT -> "ACCOUNT:" + accountId;
            case STRATEGY -> "STRATEGY:" + accountId + ":" + strategyId;
            case INSTRUMENT -> "INSTRUMENT:" + accountId + ":" + instrumentId.value();
            case DIRECTION -> "DIRECTION:" + accountId + ":" + direction.name();
        };
    }

    @Override
    public int compareTo(PortfolioBudgetKey other) {
        Objects.requireNonNull(other, "other");
        Comparator<String> nullableText = Comparator.nullsFirst(String::compareTo);
        int result = dimension.compareTo(other.dimension);
        if (result != 0) return result;
        result = accountId.compareTo(other.accountId);
        if (result != 0) return result;
        result = nullableText.compare(strategyId, other.strategyId);
        if (result != 0) return result;
        result = nullableText.compare(instrumentId == null ? null : instrumentId.value(),
                other.instrumentId == null ? null : other.instrumentId.value());
        if (result != 0) return result;
        return Comparator.nullsFirst(Comparator.<ExposureDirection>naturalOrder())
                .compare(direction, other.direction);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static void requireOnly(String strategyId, InstrumentId instrumentId,
                                    ExposureDirection direction,
                                    boolean strategyAllowed, boolean instrumentAllowed,
                                    boolean directionAllowed) {
        if (!strategyAllowed && strategyId != null) {
            throw new IllegalArgumentException("strategyId is not valid for this dimension");
        }
        if (!instrumentAllowed && instrumentId != null) {
            throw new IllegalArgumentException("instrumentId is not valid for this dimension");
        }
        if (!directionAllowed && direction != null) {
            throw new IllegalArgumentException("direction is not valid for this dimension");
        }
    }
}
