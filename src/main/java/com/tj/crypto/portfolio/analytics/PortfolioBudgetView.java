package com.tj.crypto.portfolio.analytics;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only budget view; it is never an authorization to place an order. */
public record PortfolioBudgetView(
        String snapshotId,
        long asOfTimeMs,
        String valuationCurrency,
        String sourceManifestSha256,
        AnalyticsUse permittedUse,
        BigDecimal totalGrossNotional,
        BigDecimal totalLongNotional,
        BigDecimal totalShortNotional,
        Map<PortfolioBudgetKey, PortfolioBudgetUsage> usages,
        boolean anyBreached
) {
    public PortfolioBudgetView {
        if (permittedUse != AnalyticsUse.RESEARCH_ONLY) {
            throw new IllegalArgumentException("portfolio budget views are RESEARCH_ONLY");
        }
        usages = Collections.unmodifiableMap(new LinkedHashMap<>(usages));
    }
}
