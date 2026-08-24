package com.tj.crypto.autonomy.readiness;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Exact release/account/environment/policy candidate assessed by the long-term gate. */
public record LongTermReadinessSubject(
        String subjectSha256,
        String releaseId,
        String releaseArtifactSha256,
        String accountScope,
        String environment,
        String policyVersion,
        String policyArtifactSha256,
        String agentBundleSha256,
        String legalScopeSha256,
        AutonomyEnvelope envelope) {

    public LongTermReadinessSubject {
        subjectSha256 = ReadinessValues.hash(subjectSha256, "subjectSha256");
        releaseId = ReadinessValues.required(releaseId, "releaseId", 128);
        releaseArtifactSha256 = ReadinessValues.hash(
                releaseArtifactSha256, "releaseArtifactSha256");
        accountScope = ReadinessValues.required(accountScope, "accountScope", 192);
        environment = ReadinessValues.required(environment, "environment", 32)
                .toUpperCase(Locale.ROOT);
        policyVersion = ReadinessValues.required(policyVersion, "policyVersion", 96);
        policyArtifactSha256 = ReadinessValues.hash(
                policyArtifactSha256, "policyArtifactSha256");
        agentBundleSha256 = ReadinessValues.hash(agentBundleSha256, "agentBundleSha256");
        legalScopeSha256 = ReadinessValues.hash(legalScopeSha256, "legalScopeSha256");
        envelope = Objects.requireNonNull(envelope, "envelope");
    }

    /** Digest of the complete candidate, including its immutable capital envelope. */
    public String sha256() {
        return ReadinessHashing.sha256("long-term-readiness-subject-v1", List.of(
                subjectSha256, releaseId, releaseArtifactSha256, accountScope,
                environment, policyVersion, policyArtifactSha256, agentBundleSha256,
                legalScopeSha256, envelope.sha256()));
    }
}
