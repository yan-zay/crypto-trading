package com.tj.crypto.backtest.robustness;

/** One explicitly registered member of the complete tried-strategy family. */
public record RegisteredBacktestTrial(String trialId, double sharpeRatio) {
    public RegisteredBacktestTrial {
        if (trialId == null || trialId.isBlank()) {
            throw new IllegalArgumentException("trialId must not be blank");
        }
        trialId = trialId.trim();
        if (!Double.isFinite(sharpeRatio)) {
            throw new IllegalArgumentException("sharpeRatio must be finite");
        }
    }
}
