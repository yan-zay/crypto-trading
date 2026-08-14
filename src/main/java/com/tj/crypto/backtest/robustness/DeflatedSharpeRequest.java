package com.tj.crypto.backtest.robustness;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Complete registered trial-family input for Deflated Sharpe analysis.
 *
 * @param selectedReturnPearsonKurtosis Pearson kurtosis, not excess kurtosis
 */
public record DeflatedSharpeRequest(
        String selectedTrialId,
        List<RegisteredBacktestTrial> registeredTrials,
        int selectedObservationCount,
        double selectedReturnSkewness,
        double selectedReturnPearsonKurtosis
) {
    public DeflatedSharpeRequest {
        if (selectedTrialId == null || selectedTrialId.isBlank()) {
            throw new IllegalArgumentException("selectedTrialId must not be blank");
        }
        selectedTrialId = selectedTrialId.trim();
        Objects.requireNonNull(registeredTrials, "registeredTrials");
        registeredTrials = List.copyOf(registeredTrials);
        if (registeredTrials.size() < 2) {
            throw new IllegalArgumentException("at least two registered trials are required");
        }
        if (selectedObservationCount < 2) {
            throw new IllegalArgumentException("selectedObservationCount must be at least 2");
        }
        if (!Double.isFinite(selectedReturnSkewness)) {
            throw new IllegalArgumentException("selectedReturnSkewness must be finite");
        }
        if (!Double.isFinite(selectedReturnPearsonKurtosis) || selectedReturnPearsonKurtosis <= 0) {
            throw new IllegalArgumentException("selectedReturnPearsonKurtosis must be finite and positive");
        }

        Set<String> ids = new HashSet<>();
        boolean selectedFound = false;
        for (RegisteredBacktestTrial trial : registeredTrials) {
            Objects.requireNonNull(trial, "registeredTrials must not contain null");
            if (!ids.add(trial.trialId())) {
                throw new IllegalArgumentException("duplicate registered trialId: " + trial.trialId());
            }
            if (trial.trialId().equals(selectedTrialId)) selectedFound = true;
        }
        if (!selectedFound) {
            throw new IllegalArgumentException("selectedTrialId is not present in registeredTrials");
        }
    }
}
