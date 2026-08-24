package com.tj.crypto.strategy.lifecycle;

import java.util.Optional;

/** Runtime policy port. Production wiring resolves only lifecycle-bound signed releases. */
@FunctionalInterface
public interface StrategyRuntimeReleaseGate {
    Optional<StrategyRuntimeRelease> resolve(String strategyName);

    static StrategyRuntimeReleaseGate denyAll() {
        return ignored -> Optional.empty();
    }

    /** Compatibility for explicit non-Spring unit/integration harnesses; never a Spring bean. */
    static StrategyRuntimeReleaseGate configurationOnlyCompatibility() {
        return name -> Optional.of(StrategyRuntimeRelease.configurationOnlyCompatibility(name));
    }
}
