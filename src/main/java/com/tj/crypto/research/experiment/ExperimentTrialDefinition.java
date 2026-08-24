package com.tj.crypto.research.experiment;

/** One member of the complete family frozen before the first run starts. */
public record ExperimentTrialDefinition(
        String trialId,
        int ordinal,
        String hypothesis,
        long randomSeed,
        String parameterSha256
) {
    public ExperimentTrialDefinition {
        trialId = ExperimentValidation.required(trialId, "trialId", 100);
        if (ordinal < 0) throw new IllegalArgumentException("trial ordinal must be non-negative");
        hypothesis = ExperimentValidation.required(hypothesis, "trial hypothesis", 2000);
        parameterSha256 = ExperimentValidation.sha256(parameterSha256, "parameter sha256");
    }
}
