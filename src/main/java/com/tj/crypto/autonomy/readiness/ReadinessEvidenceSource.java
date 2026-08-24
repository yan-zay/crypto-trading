package com.tj.crypto.autonomy.readiness;

import java.util.List;

/** Read-only source of immutable evidence references for one exact candidate and autonomy level. */
@FunctionalInterface
public interface ReadinessEvidenceSource {
    List<ExternalEvidenceReference> findAll(LongTermReadinessSubject subject,
                                            AutonomyLevel level);

    static ReadinessEvidenceSource empty() {
        return (subject, level) -> List.of();
    }
}
