package com.tj.crypto.portfolio.analytics;

import com.tj.crypto.common.domain.InstrumentId;
import com.tj.crypto.common.domain.TradeSide;

import java.math.BigDecimal;

/** Immutable transaction-cost analysis for one OMS order. Positive costs are unfavorable. */
public record TcaReport(
        String orderId,
        String accountId,
        String strategyId,
        InstrumentId instrumentId,
        TradeSide tradeSide,
        BigDecimal requestedQuantity,
        BigDecimal filledQuantity,
        BigDecimal fillRatio,
        BigDecimal arrivalPrice,
        BigDecimal volumeWeightedFillPrice,
        BigDecimal endMarkPrice,
        BigDecimal arrivalSlippageAmount,
        BigDecimal arrivalSlippageBps,
        BigDecimal reportedSlippageBps,
        BigDecimal feesInQuoteCurrency,
        BigDecimal opportunityCost,
        BigDecimal implementationShortfall,
        BigDecimal implementationShortfallBps,
        long firstFillLatencyMs,
        long quantityWeightedFillLatencyMs,
        long lastObservedFillLatencyMs,
        Long completionLatencyMs,
        BigDecimal marketVolumeQuantity,
        BigDecimal participationRate,
        int fillCount,
        long arrivalMarkTimeMs,
        long analysisEndTimeMs,
        long knowledgeCutoffTimeMs,
        String inputSnapshotId,
        String inputContentSha256,
        String omsManifestSha256,
        String marketManifestSha256,
        String fxManifestSha256,
        long omsWatermarkTimeMs,
        long marketWatermarkTimeMs,
        long fxWatermarkTimeMs,
        String quoteCurrency,
        TcaQuantityUnit quantityUnit,
        SnapshotCompleteness completeness,
        AnalyticsUse permittedUse
) {
    public TcaReport {
        if (completeness != SnapshotCompleteness.COMPLETE
                || permittedUse != AnalyticsUse.RESEARCH_ONLY) {
            throw new IllegalArgumentException("TCA reports must be complete and RESEARCH_ONLY");
        }
    }
}
