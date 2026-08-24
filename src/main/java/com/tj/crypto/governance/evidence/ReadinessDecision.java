package com.tj.crypto.governance.evidence;

import java.util.List;
import java.util.Map;

public record ReadinessDecision(boolean ready,
                                List<String> blockers,
                                Map<ControlKind, String> acceptedEvidence) {
    public ReadinessDecision {
        blockers = List.copyOf(blockers);
        acceptedEvidence = Map.copyOf(acceptedEvidence);
        if (ready && !blockers.isEmpty()) {
            throw new IllegalArgumentException("a ready decision cannot contain blockers");
        }
    }
}
