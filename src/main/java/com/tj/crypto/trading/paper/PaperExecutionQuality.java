package com.tj.crypto.trading.paper;

import java.math.BigDecimal;

/** Fill-derived transaction cost analysis summary. */
public record PaperExecutionQuality(
        long fills,
        BigDecimal filledQuantity,
        BigDecimal notional,
        BigDecimal fees,
        BigDecimal avgSpreadBps,
        BigDecimal avgImpactBps,
        BigDecimal avgSlippageBps,
        BigDecimal makerRatioPct
) {}
