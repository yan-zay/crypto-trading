package com.tj.crypto.autonomy.capital;

import java.math.BigDecimal;
import java.util.Objects;

/** Exact quantity paired with its semantic unit. */
public record Quantity(BigDecimal amount, QuantityUnit unit) {
    public Quantity {
        amount = CapitalValues.decimal(amount, "amount");
        Objects.requireNonNull(unit, "unit");
    }
}
