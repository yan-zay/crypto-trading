package com.tj.crypto.backtest.robustness;

import java.math.BigDecimal;
import java.util.List;

/** One combinatorially symmetric in-sample/out-of-sample selection result. */
public record CscvSplitResult(
        int splitIndex,
        List<Integer> inSamplePartitions,
        String selectedTrialId,
        BigDecimal inSampleMeanReturn,
        BigDecimal outOfSampleMeanReturn,
        BigDecimal outOfSampleRankFromWorst,
        BigDecimal outOfSampleRelativeRank,
        BigDecimal rankLogit,
        boolean overfit
) {
    public CscvSplitResult {
        inSamplePartitions = List.copyOf(inSamplePartitions);
    }
}
