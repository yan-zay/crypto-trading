package com.tj.crypto.autonomy.agent;

import java.time.Instant;

/**
 * 调用方携带的不可变授权制品引用。
 *
 * <p>字段全部是不可信声明，必须交给 {@link TrustedAuthorizationVerifier} 从独立信任源验证。
 */
public record AuthorizationReference(
        String tokenId,
        String artifactSha256,
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
        String claimedAuthorizingPrincipal,
        Instant expiresAt) {

    /** L3 没有运行期风险快照时使用的显式空上下文摘要。 */
    public static final String EMPTY_CONTEXT_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    public AuthorizationReference {
        tokenId = AutonomyValues.text(tokenId, "tokenId");
        artifactSha256 = AutonomyValues.digest(artifactSha256, "artifactSha256");
        if (kind == null) throw new IllegalArgumentException("kind is required");
        audience = AutonomyValues.text(audience, "audience");
        requestingOperator = AutonomyValues.text(requestingOperator, "requestingOperator");
        requestingAgentIdentity = AutonomyValues.text(requestingAgentIdentity, "requestingAgentIdentity");
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
        claimedAuthorizingPrincipal = AutonomyValues.text(
                claimedAuthorizingPrincipal, "claimedAuthorizingPrincipal");
        if (expiresAt == null) throw new IllegalArgumentException("expiresAt is required");
    }
}
