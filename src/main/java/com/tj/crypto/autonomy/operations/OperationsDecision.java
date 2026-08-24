package com.tj.crypto.autonomy.operations;

import java.util.List;

/** Complete result of one pure state transition. It performs no action itself. */
public record OperationsDecision(
        boolean accepted,
        boolean mustApply,
        OperationsReason reason,
        String detail,
        OperationsSnapshot snapshot,
        List<OperationsActionIntent> actionIntents
) {
    public OperationsDecision {
        if (reason == null || snapshot == null) {
            throw new IllegalArgumentException("decision reason and snapshot are required");
        }
        detail = detail == null ? "" : detail;
        actionIntents = actionIntents == null ? List.of() : List.copyOf(actionIntents);
        if (mustApply == actionIntents.isEmpty()) {
            throw new IllegalArgumentException(
                    "applicable transitions need intents; idempotent no-ops must not emit intents");
        }
    }
}
