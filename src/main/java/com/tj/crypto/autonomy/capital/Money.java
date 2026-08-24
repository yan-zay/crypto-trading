package com.tj.crypto.autonomy.capital;

import java.math.BigDecimal;
import java.util.Objects;

/** Exact monetary/asset amount. Binary floating-point inputs are deliberately unsupported. */
public record Money(BigDecimal amount, AssetUnit unit) implements Comparable<Money> {
    public Money {
        amount = CapitalValues.decimal(amount, "amount");
        Objects.requireNonNull(unit, "unit");
    }

    public static Money zero(AssetUnit unit) {
        return new Money(BigDecimal.ZERO, unit);
    }

    public Money add(Money other) {
        requireSameUnit(other);
        return new Money(amount.add(other.amount), unit);
    }

    public Money subtract(Money other) {
        requireSameUnit(other);
        return new Money(amount.subtract(other.amount), unit);
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNonNegative() {
        return amount.signum() >= 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameUnit(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameUnit(Money other) {
        Objects.requireNonNull(other, "other");
        if (!unit.equals(other.unit)) {
            throw new IllegalArgumentException("money units differ: " + unit.code()
                    + " != " + other.unit.code());
        }
    }
}
