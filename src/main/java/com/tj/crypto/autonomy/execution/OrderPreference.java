package com.tj.crypto.autonomy.execution;

import java.math.BigDecimal;
import java.util.Objects;

/** Target-position compiler hint; it is not an order or dispatch permission. */
public record OrderPreference(
        ExecutionOrderType orderType,
        ExecutionTimeInForce timeInForce,
        BigDecimal limitPrice
) {
    public OrderPreference {
        Objects.requireNonNull(orderType, "orderType");
        Objects.requireNonNull(timeInForce, "timeInForce");
        if (orderType == ExecutionOrderType.LIMIT) {
            limitPrice = ExecutionValues.decimal(limitPrice, "limitPrice");
        } else if (limitPrice != null) {
            throw new IllegalArgumentException("market preference cannot contain a limit price");
        }
    }
}
