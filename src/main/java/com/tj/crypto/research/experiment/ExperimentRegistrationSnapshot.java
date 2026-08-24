package com.tj.crypto.research.experiment;

public record ExperimentRegistrationSnapshot(
        String registrationId,
        String idempotencyKey,
        String registrationSha256,
        String experimentName,
        String hypothesis,
        String protocolVersion,
        String modelVersion,
        String promptVersion,
        int declaredTrialCount,
        String registeredBy,
        long registeredAtEpochMillis
) {
}
