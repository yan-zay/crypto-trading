package com.tj.crypto.research.experiment;

/** Deterministic state machine for one pre-registered trial. */
public enum ExperimentState {
    REGISTERED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    public boolean mayTransitionTo(ExperimentState target) {
        if (target == null || terminal()) return false;
        return switch (this) {
            case REGISTERED -> target == RUNNING || target == FAILED || target == CANCELLED;
            case RUNNING -> target == SUCCEEDED || target == FAILED || target == CANCELLED;
            default -> false;
        };
    }
}
