package com.tj.crypto.execution.cost;

import java.math.BigDecimal;

/** Immutable fill estimate, including the unfilled remainder and cost decomposition. */
public record ExecutionFillPlan(
        boolean marketable,
        BigDecimal filledQuantity,
        BigDecimal remainingQuantity,
        BigDecimal fillPrice,
        BigDecimal spreadBps,
        BigDecimal impactBps,
        BigDecimal latencyBps,
        BigDecimal totalSlippageBps,
        BigDecimal capacityNotional,
        String liquidityRole
) {
    public boolean hasFill() {
        return marketable && filledQuantity != null && filledQuantity.signum() > 0;
    }
}
