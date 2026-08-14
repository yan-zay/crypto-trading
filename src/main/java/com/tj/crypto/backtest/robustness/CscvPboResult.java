package com.tj.crypto.backtest.robustness;

import java.math.BigDecimal;
import java.util.List;

/** Probability of Backtest Overfitting report from CSCV trial selection. */
public record CscvPboResult(
        int observationCount,
        int registeredTrialCount,
        int partitionCount,
        int evaluatedCombinations,
        BigDecimal probabilityOfBacktestOverfitting,
        BigDecimal medianRankLogit,
        List<CscvSplitResult> splits,
        List<String> limitations
) {
    public CscvPboResult {
        splits = List.copyOf(splits);
        limitations = List.copyOf(limitations);
    }
}
