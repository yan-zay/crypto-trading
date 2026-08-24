package com.tj.crypto.research.experiment;

public record ExperimentRegistrationReceipt(
        String registrationId,
        String registrationSha256,
        long registeredAtEpochMillis,
        boolean idempotentReplay
) {
}
