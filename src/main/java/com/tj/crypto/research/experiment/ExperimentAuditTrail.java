package com.tj.crypto.research.experiment;

import java.util.List;
import java.util.Map;

/** Read-only audit projection; source rows remain append-only. */
public record ExperimentAuditTrail(
        ExperimentRegistrationSnapshot registration,
        List<ExperimentArtifactReference> artifacts,
        List<ExperimentTrialDefinition> trials,
        List<ExperimentEventAudit> events,
        Map<String, ExperimentState> trialStates,
        ExperimentFamilyState familyState,
        long chainHeadSequence,
        String chainHeadSha256,
        boolean completeTrialFamily,
        boolean auditChainValid
) {
    public ExperimentAuditTrail {
        artifacts = List.copyOf(artifacts);
        trials = List.copyOf(trials);
        events = List.copyOf(events);
        trialStates = Map.copyOf(trialStates);
    }
}
