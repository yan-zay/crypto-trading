package com.tj.crypto.autonomy.agent;

import java.time.Instant;

/** 发送给独立 verifier 的完整预期边界；verifier 必须原子消费 token。 */
public record AuthorizationVerificationRequest(
        String useId,
        AuthorizationReference claimedReference,
        AuthorizationKind expectedKind,
        String expectedAudience,
        String requestingOperator,
        String agentIdentity,
        String deploymentEnvironment,
        String governanceSubjectSha256,
        String proposalId,
        String proposalSha256,
        String policyId,
        String policyVersion,
        String policyArtifactSha256,
        String contextSha256,
        Instant proposalExpiresAt) {

    public AuthorizationVerificationRequest {
        useId = AutonomyValues.text(useId, "useId");
        if (claimedReference == null || expectedKind == null) {
            throw new IllegalArgumentException("claimedReference and expectedKind are required");
        }
        expectedAudience = AutonomyValues.text(expectedAudience, "expectedAudience");
        requestingOperator = AutonomyValues.text(requestingOperator, "requestingOperator");
        agentIdentity = AutonomyValues.text(agentIdentity, "agentIdentity");
        deploymentEnvironment = AutonomyValues.text(
                deploymentEnvironment, "deploymentEnvironment").toUpperCase(java.util.Locale.ROOT);
        governanceSubjectSha256 = AutonomyValues.digest(
                governanceSubjectSha256, "governanceSubjectSha256");
        proposalId = AutonomyValues.text(proposalId, "proposalId");
        proposalSha256 = AutonomyValues.digest(proposalSha256, "proposalSha256");
        policyId = AutonomyValues.text(policyId, "policyId");
        policyVersion = AutonomyValues.text(policyVersion, "policyVersion");
        policyArtifactSha256 = AutonomyValues.digest(policyArtifactSha256, "policyArtifactSha256");
        contextSha256 = AutonomyValues.digest(contextSha256, "contextSha256");
        if (proposalExpiresAt == null) throw new IllegalArgumentException("proposalExpiresAt is required");
    }
}
