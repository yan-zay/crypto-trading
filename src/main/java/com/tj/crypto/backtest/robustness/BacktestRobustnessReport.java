package com.tj.crypto.backtest.robustness;

import java.math.BigDecimal;
import java.util.List;

public record BacktestRobustnessReport(
        int tradeCount,
        int bootstrapSamples,
        long randomSeed,
        BigDecimal meanTradeReturn,
        BigDecimal standardDeviation,
        BigDecimal skewness,
        BigDecimal excessKurtosis,
        BigDecimal bootstrapMeanLower95,
        BigDecimal bootstrapMeanUpper95,
        BigDecimal probabilityMeanPositive,
        BigDecimal probabilisticSharpeRatio,
        BigDecimal minimumTrackRecordLength,
        String evidenceGrade,
        List<String> warnings
) {}
