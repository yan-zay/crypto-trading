package com.tj.crypto.strategy.lifecycle;

import java.time.Instant;

public record LifecycleDecision(
        boolean allowed,
        String strategyName,
        String artifactSha256,
        StrategyLifecycleStage stage,
        LifecycleReason reason,
        String detail,
        Instant decidedAt) {
}
