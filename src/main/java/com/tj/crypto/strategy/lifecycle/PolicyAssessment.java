package com.tj.crypto.strategy.lifecycle;

import java.util.Set;

record PolicyAssessment(boolean allowed, LifecycleReason reason, String detail,
                        String evidenceSetSha256, Set<StrategyEvidenceType> requiredEvidence) {
}
