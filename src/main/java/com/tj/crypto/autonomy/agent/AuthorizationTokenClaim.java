package com.tj.crypto.autonomy.agent;

import java.time.Instant;

/** 持久防重放存储必须原子记录的完整授权消费绑定。 */
public record AuthorizationTokenClaim(
        String tokenId,
        String useId,
        AuthorizationKind kind,
        String audience,
        String requestingOperator,
        String requestingAgentIdentity,
        String deploymentEnvironment,
        String governanceSubjectSha256,
        String proposalId,
        String proposalSha256,
        String policyId,
        String policyVersion,
        String policyArtifactSha256,
        String contextSha256,
        String verificationReceiptSha256,
        Instant claimedAt) {

    public AuthorizationTokenClaim {
        tokenId = AutonomyValues.text(tokenId, "tokenId");
        useId = AutonomyValues.text(useId, "useId");
        if (kind == null) throw new IllegalArgumentException("kind is required");
        audience = AutonomyValues.text(audience, "audience");
        requestingOperator = AutonomyValues.text(requestingOperator, "requestingOperator");
        requestingAgentIdentity = AutonomyValues.text(
                requestingAgentIdentity, "requestingAgentIdentity");
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
        verificationReceiptSha256 = AutonomyValues.digest(
                verificationReceiptSha256, "verificationReceiptSha256");
        if (claimedAt == null) throw new IllegalArgumentException("claimedAt is required");
    }

    static AuthorizationTokenClaim from(
            AuthorizationVerificationRequest request,
            VerifiedAuthorization verified,
            Instant claimedAt) {
        return new AuthorizationTokenClaim(verified.tokenId(), request.useId(), request.expectedKind(),
                request.expectedAudience(), request.requestingOperator(), request.agentIdentity(),
                request.deploymentEnvironment(), request.governanceSubjectSha256(), request.proposalId(),
                request.proposalSha256(), request.policyId(), request.policyVersion(),
                request.policyArtifactSha256(), request.contextSha256(),
                verified.verificationReceiptSha256(), claimedAt);
    }
}
