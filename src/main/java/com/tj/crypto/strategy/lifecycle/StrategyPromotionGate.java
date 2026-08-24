package com.tj.crypto.strategy.lifecycle;

import org.springframework.stereotype.Component;

import java.util.Objects;

/** Exact-release runtime gate; callers must present the signed artifact digest, not just a name. */
@Component
public class StrategyPromotionGate {
    private final StrategyLifecycleService lifecycleService;

    public StrategyPromotionGate(StrategyLifecycleService lifecycleService) {
        this.lifecycleService = Objects.requireNonNull(lifecycleService, "lifecycleService");
    }

    public boolean allows(String strategyName, String artifactSha256,
                          StrategyLifecycleStage minimumStage) {
        Hashes.requireSha256(artifactSha256, "artifactSha256");
        if (minimumStage == null || minimumStage == StrategyLifecycleStage.ROLLED_BACK) {
            throw new IllegalArgumentException("a runnable minimum stage is required");
        }
        StrategyLifecycleState state = lifecycleService.current(strategyName);
        return state.artifactSha256().equals(artifactSha256)
                && state.stage() != StrategyLifecycleStage.ROLLED_BACK
                && state.stage().ordinal() >= minimumStage.ordinal();
    }
}
