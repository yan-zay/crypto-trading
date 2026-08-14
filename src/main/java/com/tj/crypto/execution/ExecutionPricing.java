package com.tj.crypto.execution;

import java.math.BigDecimal;

/** Quantity and decomposed adverse execution costs for one marketable order slice. */
public record ExecutionPricing(
        BigDecimal filledQuantity,
        BigDecimal fillPrice,
        BigDecimal spreadBps,
        BigDecimal fixedSlippageBps,
        BigDecimal impactBps,
        BigDecimal participationRate,
        BigDecimal capacityQuantity
) {}
