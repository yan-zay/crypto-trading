package com.tj.crypto.backtest.robustness;

import java.math.BigDecimal;
import java.util.List;

/** Auditable selection-bias-adjusted Sharpe evidence. */
public record DeflatedSharpeResult(
        String selectedTrialId,
        int registeredTrialCount,
        int selectedObservationCount,
        BigDecimal selectedSharpeRatio,
        BigDecimal trialFamilySharpeVariance,
        BigDecimal expectedMaximumSharpe,
        BigDecimal deflatedSharpeProbability,
        boolean exceedsNinetyFivePercent,
        List<String> limitations
) {
    public DeflatedSharpeResult {
        limitations = List.copyOf(limitations);
    }
}
