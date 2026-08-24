package com.tj.crypto.portfolio.analytics;

import java.math.BigDecimal;

/** Utilization of one configured budget. Negative remaining means the budget is breached. */
public record PortfolioBudgetUsage(
        PortfolioBudgetKey key,
        BigDecimal usedNotional,
        BigDecimal limitNotional,
        BigDecimal remainingNotional,
        BigDecimal utilization,
        boolean breached
) {
}
