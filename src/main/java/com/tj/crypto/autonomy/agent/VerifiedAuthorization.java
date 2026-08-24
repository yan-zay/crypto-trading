package com.tj.crypto.autonomy.agent;

import java.time.Instant;

/** 独立 verifier 从可信制品、身份源和一次性消费存储得到的回执。 */
public record VerifiedAuthorization(
        String tokenId,
        String artifactSha256,
        AuthorizationKind kind,
        String audience,
        String requestingOperator,
        String authenticatedRequestingAgentIdentity,
        String deploymentEnvironment,
        String governanceSubjectSha256,
        String proposalId,
        String proposalSha256,
        String policyId,
        String policyVersion,
        String policyArtifactSha256,
        String contextSha256,
        String authenticatedAuthorizingPrincipal,
        AuthorizationPrincipalKind principalKind,
        String consumedUseId,
        Instant expiresAt,
        String verificationReceiptSha256) {

    public VerifiedAuthorization {
        tokenId = AutonomyValues.text(tokenId, "tokenId");
        artifactSha256 = AutonomyValues.digest(artifactSha256, "artifactSha256");
        if (kind == null || principalKind == null) {
            throw new IllegalArgumentException("kind and principalKind are required");
        }
        audience = AutonomyValues.text(audience, "audience");
        requestingOperator = AutonomyValues.text(requestingOperator, "requestingOperator");
        authenticatedRequestingAgentIdentity = AutonomyValues.text(
                authenticatedRequestingAgentIdentity, "authenticatedRequestingAgentIdentity");
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
        authenticatedAuthorizingPrincipal = AutonomyValues.text(
                authenticatedAuthorizingPrincipal, "authenticatedAuthorizingPrincipal");
        consumedUseId = AutonomyValues.text(consumedUseId, "consumedUseId");
        if (expiresAt == null) throw new IllegalArgumentException("expiresAt is required");
        verificationReceiptSha256 = AutonomyValues.digest(
                verificationReceiptSha256, "verificationReceiptSha256");
    }

    /** 对 verifier 回执进行第二次逐字段比较，防止返回错误制品或错误主体。 */
    public void requireExactMatch(AuthorizationVerificationRequest request) {
        if (request == null) throw violation("AUTHORIZATION_REQUEST_MISSING", "Authorization request is missing");
        AuthorizationReference reference = request.claimedReference();
        if (!tokenId.equals(reference.tokenId()) || !artifactSha256.equals(reference.artifactSha256())) {
            throw violation("AUTHORIZATION_ARTIFACT_MISMATCH", "Verified authorization artifact does not match");
        }
        if (kind != request.expectedKind() || kind != reference.kind()) {
            throw violation("AUTHORIZATION_KIND_MISMATCH", "Authorization kind does not match");
        }
        if (!audience.equals(request.expectedAudience()) || !audience.equals(reference.audience())) {
            throw violation("AUTHORIZATION_AUDIENCE_MISMATCH", "Authorization audience does not match");
        }
        if (!requestingOperator.equals(request.requestingOperator())
                || !requestingOperator.equals(reference.requestingOperator())) {
            throw violation("AUTHORIZATION_OPERATOR_MISMATCH", "Authorization operator does not match");
        }
        if (!authenticatedRequestingAgentIdentity.equals(request.agentIdentity())
                || !authenticatedRequestingAgentIdentity.equals(reference.requestingAgentIdentity())) {
            throw violation("AUTHORIZATION_AGENT_IDENTITY_MISMATCH",
                    "Authorization does not authenticate the requesting Agent identity");
        }
        if (!deploymentEnvironment.equals(request.deploymentEnvironment())
                || !deploymentEnvironment.equals(reference.deploymentEnvironment())
                || !governanceSubjectSha256.equals(request.governanceSubjectSha256())
                || !governanceSubjectSha256.equals(reference.governanceSubjectSha256())) {
            throw violation("AUTHORIZATION_GOVERNANCE_CONTEXT_MISMATCH",
                    "Authorization environment or governance subject does not match");
        }
        if (!proposalId.equals(request.proposalId()) || !proposalId.equals(reference.proposalId())
                || !proposalSha256.equals(request.proposalSha256())
                || !proposalSha256.equals(reference.proposalSha256())) {
            throw violation("AUTHORIZATION_PROPOSAL_MISMATCH", "Authorization does not bind the full proposal");
        }
        if (!policyId.equals(request.policyId()) || !policyId.equals(reference.policyId())
                || !policyVersion.equals(request.policyVersion())
                || !policyVersion.equals(reference.policyVersion())
                || !policyArtifactSha256.equals(request.policyArtifactSha256())
                || !policyArtifactSha256.equals(reference.policyArtifactSha256())) {
            throw violation("AUTHORIZATION_POLICY_MISMATCH", "Authorization policy binding does not match");
        }
        if (!contextSha256.equals(request.contextSha256())
                || !contextSha256.equals(reference.contextSha256())) {
            throw violation("AUTHORIZATION_CONTEXT_MISMATCH", "Authorization context does not match");
        }
        if (!authenticatedAuthorizingPrincipal.equals(reference.claimedAuthorizingPrincipal())) {
            throw violation("AUTHORIZATION_PRINCIPAL_MISMATCH", "Authenticated principal does not match reference");
        }
        if (!consumedUseId.equals(request.useId())) {
            throw violation("AUTHORIZATION_SINGLE_USE_MISMATCH", "Token was not consumed for the expected use");
        }
        if (!expiresAt.equals(reference.expiresAt()) || request.proposalExpiresAt().isAfter(expiresAt)) {
            throw violation("AUTHORIZATION_EXPIRED", "Authorization does not cover the proposal lifetime");
        }
    }

    public void requireIndependentFrom(AuthorizationVerificationRequest request) {
        if (authenticatedAuthorizingPrincipal.equalsIgnoreCase(authenticatedRequestingAgentIdentity)
                || authenticatedAuthorizingPrincipal.equalsIgnoreCase(request.requestingOperator())) {
            throw violation("AUTHORIZATION_NOT_INDEPENDENT",
                    "Authorizing principal must be independent from the Agent and requesting operator");
        }
    }

    private static AgentAutonomyViolationException violation(String code, String message) {
        return new AgentAutonomyViolationException(code, message);
    }
}
