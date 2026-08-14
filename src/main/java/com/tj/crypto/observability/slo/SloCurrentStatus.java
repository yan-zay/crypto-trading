package com.tj.crypto.observability.slo;

import java.math.BigDecimal;

/** Current rolling-window SLO calculation. */
public record SloCurrentStatus(
        String name,
        long windowStartMs,
        long windowEndMs,
        BigDecimal targetValue,
        BigDecimal actualValue,
        boolean compliant,
        BigDecimal errorBudgetRemainingPct,
        long sampleCount,
        long successCount,
        long failureCount,
        double averageLatencyMs,
        long maxLatencyMs,
        String state
) {
}
