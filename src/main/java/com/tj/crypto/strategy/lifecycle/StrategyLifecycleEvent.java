package com.tj.crypto.strategy.lifecycle;

import java.time.Instant;

/** Append-only, hash-chained audit record for every accepted and denied operation. */
public record StrategyLifecycleEvent(
        String eventId,
        String strategyName,
        String artifactSha256,
        long sequenceNo,
        long reviewCycle,
        StrategyLifecycleStage fromStage,
        StrategyLifecycleStage toStage,
        StrategyLifecycleAction action,
        boolean accepted,
        LifecycleReason reason,
        String detail,
        String operator,
        String evidenceSetSha256,
        Instant occurredAt,
        String previousEventSha256,
        String eventSha256) {
}
