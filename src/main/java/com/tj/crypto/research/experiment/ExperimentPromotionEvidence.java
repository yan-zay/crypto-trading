package com.tj.crypto.research.experiment;

import java.util.List;

/** Fail-closed evidence projection consumed by deterministic promotion policy gates. */
public record ExperimentPromotionEvidence(
        String registrationId,
        String registrationSha256,
        boolean completeTrialFamily,
        boolean allTrialsTerminal,
        boolean allTrialsSucceeded,
        boolean auditChainValid,
        ExperimentCertificationEvidence certification,
        boolean eligible,
        List<String> reasons
) {
    public ExperimentPromotionEvidence {
        reasons = List.copyOf(reasons);
    }

    /** Backward-compatible projection constructor; only the registry service grants eligibility. */
    public ExperimentPromotionEvidence(
            String registrationId, String registrationSha256,
            boolean completeTrialFamily, boolean allTrialsTerminal,
            boolean allTrialsSucceeded, boolean auditChainValid,
            boolean eligible, List<String> reasons) {
        this(registrationId, registrationSha256, completeTrialFamily, allTrialsTerminal,
                allTrialsSucceeded, auditChainValid,
                ExperimentCertificationEvidence.notVerified(registrationId,
                        registrationSha256, "LEGACY_CERTIFICATION_NOT_VERIFIED"),
                eligible, reasons);
    }
}
