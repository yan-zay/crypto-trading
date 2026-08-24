package com.tj.crypto.autonomy.readiness;

import java.util.Objects;

/** Separate, independently verified L3 and L4 decisions for one atomic assessment. */
public final class LongTermReadinessAssessment {
    private final LongTermReadinessDecision l3Advisory;
    private final LongTermReadinessDecision l4BoundedAutonomy;

    private LongTermReadinessAssessment(LongTermReadinessDecision l3Advisory,
                                        LongTermReadinessDecision l4BoundedAutonomy) {
        this.l3Advisory = Objects.requireNonNull(l3Advisory, "l3Advisory");
        this.l4BoundedAutonomy = Objects.requireNonNull(
                l4BoundedAutonomy, "l4BoundedAutonomy");
        if (l3Advisory.autonomyLevel() != AutonomyLevel.L3_ADVISORY
                || l4BoundedAutonomy.autonomyLevel()
                != AutonomyLevel.L4_BOUNDED_AUTONOMY
                || !l3Advisory.readinessSubjectSha256().equals(
                l4BoundedAutonomy.readinessSubjectSha256())) {
            throw new IllegalArgumentException("assessment decisions must bind the same subject");
        }
        if (!l4BoundedAutonomy.prerequisiteDecisionSha256().equals(
                ReadinessDecisionHashing.prerequisite(l3Advisory))) {
            throw new IllegalArgumentException("L4 decision is bound to a different L3 decision");
        }
        if (l4BoundedAutonomy.wasGoAtDecisionTime()
                && (!l3Advisory.readyAt(l4BoundedAutonomy.decidedAt())
                || l4BoundedAutonomy.expiresAt().isAfter(l3Advisory.expiresAt()))) {
            throw new IllegalArgumentException("L4 GO requires the same unexpired L3 prerequisite");
        }
    }

    static LongTermReadinessAssessment create(LongTermReadinessDecision l3Advisory,
                                              LongTermReadinessDecision l4BoundedAutonomy) {
        return new LongTermReadinessAssessment(l3Advisory, l4BoundedAutonomy);
    }

    public LongTermReadinessDecision l3Advisory() {
        return l3Advisory;
    }

    public LongTermReadinessDecision l4BoundedAutonomy() {
        return l4BoundedAutonomy;
    }
}
