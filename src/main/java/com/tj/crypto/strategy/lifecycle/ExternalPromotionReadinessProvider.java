package com.tj.crypto.strategy.lifecycle;

import com.tj.crypto.governance.evidence.GovernanceSubject;

import java.time.Instant;

/**
 * Integration port for real legal/security/operations/live-truth evidence.
 * Implementations must fail closed on lookup errors and return a content-addressed snapshot.
 */
public interface ExternalPromotionReadinessProvider {
    /**
     * Assess one exact immutable candidate. A strategy name or environment alone is deliberately
     * insufficient because that would allow evidence from another build/account/configuration to
     * be replayed during promotion.
     */
    ExternalPromotionReadiness assess(GovernanceSubject subject, Instant now);
}
