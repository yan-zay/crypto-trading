package com.tj.crypto.research.certification;

import java.util.List;

/** Deterministic net-return evidence for one pre-registered trial. */
public record TrialEvaluation(
        String trialId,
        TrialRunStatus status,
        List<Double> chronologicalNetReturns,
        List<String> failureReasons
) {
    public TrialEvaluation {
        chronologicalNetReturns = List.copyOf(chronologicalNetReturns);
        failureReasons = List.copyOf(failureReasons);
        if (status == TrialRunStatus.COMPLETED && !failureReasons.isEmpty()) {
            throw new IllegalArgumentException("completed trial may not have failure reasons");
        }
        if (status == TrialRunStatus.FAILED && failureReasons.isEmpty()) {
            throw new IllegalArgumentException("failed trial must record a reason");
        }
    }

    public enum TrialRunStatus { COMPLETED, FAILED }
}
