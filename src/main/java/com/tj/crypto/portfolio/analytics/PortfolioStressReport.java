package com.tj.crypto.portfolio.analytics;

import java.math.BigDecimal;
import java.util.List;

/** Ordered scenario results and the deterministic worst-loss scenario. */
public record PortfolioStressReport(
        String snapshotId,
        long asOfTimeMs,
        String valuationCurrency,
        String sourceManifestSha256,
        String equityLedgerManifestSha256,
        String equityContentSha256,
        AnalyticsUse permittedUse,
        BigDecimal initialEquity,
        List<StressScenarioResult> scenarios,
        StressScenarioResult worstScenario
) {
    public PortfolioStressReport {
        if (permittedUse != AnalyticsUse.RESEARCH_ONLY) {
            throw new IllegalArgumentException("stress reports are RESEARCH_ONLY");
        }
        scenarios = List.copyOf(scenarios);
        if (!scenarios.contains(worstScenario)) {
            throw new IllegalArgumentException("worstScenario must be present in scenarios");
        }
    }
}
