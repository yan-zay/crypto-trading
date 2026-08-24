package com.tj.crypto.autonomy.readiness;

import java.util.List;

final class ReadinessDecisionHashing {
    private ReadinessDecisionHashing() {
    }

    static String prerequisite(LongTermReadinessDecision l3) {
        return ReadinessHashing.sha256("long-term-readiness-l3-prerequisite-v1", List.of(
                l3.readinessSubjectSha256(), l3.verdict().name(), l3.evidenceSetSha256(),
                l3.decidedAt().toString(), l3.expiresAt().toString()));
    }
}
