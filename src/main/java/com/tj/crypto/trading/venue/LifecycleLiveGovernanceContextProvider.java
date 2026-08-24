package com.tj.crypto.trading.venue;

import com.tj.crypto.strategy.lifecycle.StrategyLifecycleStage;
import com.tj.crypto.strategy.lifecycle.StrategyLifecycleStore;
import com.tj.crypto.strategy.lifecycle.StrategyPromotionGate;
import com.tj.crypto.strategy.lifecycle.StrategyReleaseAssignment;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Resolves the full immutable assignment and independently checks the current lifecycle stage. */
@Component
final class LifecycleLiveGovernanceContextProvider implements LiveGovernanceContextProvider {
    private final StrategyLifecycleStore store;
    private final StrategyPromotionGate promotionGate;

    LifecycleLiveGovernanceContextProvider(StrategyLifecycleStore store,
                                           StrategyPromotionGate promotionGate) {
        this.store = store;
        this.promotionGate = promotionGate;
    }

    @Override
    public ResolvedLiveGovernanceContext requireCurrentCanary(String strategyName, long nowMs) {
        try {
            Instant now = Instant.ofEpochMilli(nowMs);
            StrategyReleaseAssignment assignment = store.findReleaseAssignment(strategyName)
                    .orElseThrow(() -> new LiveAuthorizationException(
                            "Exact strategy release assignment is NOT_VERIFIED"));
            if (!assignment.currentAt(now)) {
                throw new LiveAuthorizationException("Strategy release assignment is expired");
            }
            if (!promotionGate.allows(strategyName, assignment.artifactSha256(),
                    StrategyLifecycleStage.CANARY)) {
                throw new LiveAuthorizationException("Strategy release is not currently CANARY-ready");
            }
            return new ResolvedLiveGovernanceContext(assignment.assignmentId(),
                    assignment.assignmentVersion(), strategyName, assignment.artifactSha256(),
                    StrategyLifecycleStage.CANARY, assignment.governanceSubject(),
                    assignment.expiresAt());
        } catch (LiveAuthorizationException denied) {
            throw denied;
        } catch (RuntimeException failure) {
            throw new LiveAuthorizationException("Strategy release lookup failed closed");
        }
    }
}
