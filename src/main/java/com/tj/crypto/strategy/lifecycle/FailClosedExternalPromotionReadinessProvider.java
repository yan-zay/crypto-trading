package com.tj.crypto.strategy.lifecycle;

import com.tj.crypto.governance.evidence.GovernanceSubject;

import java.time.Instant;
import java.util.List;

/** Safe default until an adapter to independently attested governance evidence is installed. */
class FailClosedExternalPromotionReadinessProvider implements ExternalPromotionReadinessProvider {
    @Override
    public ExternalPromotionReadiness assess(GovernanceSubject subject, Instant now) {
        return new ExternalPromotionReadiness(false, null, now, now,
                List.of("external legal/security/operations/live-truth evidence provider is not configured"));
    }
}
