package com.tj.crypto.strategy.lifecycle;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/** Fail-closed runtime gate backed by the assignment and lifecycle policy state. */
@Component
final class LifecycleStrategyRuntimeReleaseGate implements StrategyRuntimeReleaseGate {
    private final StrategyLifecycleStore store;
    private final StrategyPromotionGate promotionGate;
    private final Clock clock;

    LifecycleStrategyRuntimeReleaseGate(StrategyLifecycleStore store,
                                        StrategyPromotionGate promotionGate) {
        this(store, promotionGate, Clock.systemUTC());
    }

    LifecycleStrategyRuntimeReleaseGate(StrategyLifecycleStore store,
                                        StrategyPromotionGate promotionGate,
                                        Clock clock) {
        this.store = store;
        this.promotionGate = promotionGate;
        this.clock = clock;
    }

    @Override
    public Optional<StrategyRuntimeRelease> resolve(String strategyName) {
        try {
            Instant now = clock.instant();
            StrategyReleaseAssignment assignment = store.findReleaseAssignment(strategyName)
                    .orElse(null);
            if (assignment == null || !assignment.currentAt(now)) return Optional.empty();
            if (!promotionGate.allows(strategyName, assignment.artifactSha256(),
                    assignment.minimumStage())) return Optional.empty();
            return Optional.of(new StrategyRuntimeRelease(assignment.assignmentId(),
                    assignment.artifactSha256(), assignment.governanceSubject().sha256(),
                    assignment.minimumStage(), true));
        } catch (RuntimeException failClosed) {
            return Optional.empty();
        }
    }
}
