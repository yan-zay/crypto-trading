package com.tj.crypto.autonomy.agent;

import java.time.Instant;

/**
 * L4 确定性 gate 签发的短期、单次、内容绑定能力；它只可被本包消费为审计回执，不能发送订单。
 */
public record L4CapabilityToken(
        String tokenId,
        String tokenSha256,
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
        String riskReservationSha256,
        String authorizationReceiptSha256,
        String contextSha256,
        Instant issuedAt,
        Instant expiresAt) {

    private static final String HASH_DOMAIN = "agent-l4-capability-token-v1";

    public L4CapabilityToken {
        tokenId = AutonomyValues.text(tokenId, "tokenId");
        tokenSha256 = AutonomyValues.digest(tokenSha256, "tokenSha256");
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
        riskReservationSha256 = AutonomyValues.digest(
                riskReservationSha256, "riskReservationSha256");
        authorizationReceiptSha256 = AutonomyValues.digest(
                authorizationReceiptSha256, "authorizationReceiptSha256");
        contextSha256 = AutonomyValues.digest(contextSha256, "contextSha256");
        if (issuedAt == null || expiresAt == null || !expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("capability token must have a positive lifetime");
        }
        String expected = hash(tokenId, audience, requestingOperator, requestingAgentIdentity,
                deploymentEnvironment,
                governanceSubjectSha256, proposalId, proposalSha256,
                policyId, policyVersion, policyArtifactSha256, riskReservationSha256,
                authorizationReceiptSha256,
                contextSha256, issuedAt, expiresAt);
        if (!expected.equals(tokenSha256)) {
            throw new IllegalArgumentException("tokenSha256 must bind the complete capability token");
        }
    }

    static L4CapabilityToken issue(
            String tokenId,
            String operator,
            TradeProposal proposal,
            String riskReservationSha256,
            String authorizationReceiptSha256,
            String contextSha256,
            Instant issuedAt,
            Instant expiresAt) {
        String id = AutonomyValues.text(tokenId, "tokenId");
        String receipt = AutonomyValues.digest(authorizationReceiptSha256, "authorizationReceiptSha256");
        String reservation = AutonomyValues.digest(riskReservationSha256, "riskReservationSha256");
        String context = AutonomyValues.digest(contextSha256, "contextSha256");
        String digest = hash(id, TrustedAuthorizationVerifier.L4_AUDIENCE, operator,
                proposal.agentIdentity(),
                proposal.governanceContext().deploymentEnvironment(),
                proposal.governanceContext().governanceSubjectSha256(),
                proposal.proposalId(), proposal.proposalSha256(), proposal.policyId(),
                proposal.policyVersion(), proposal.policyArtifactSha256(), reservation, receipt, context,
                issuedAt, expiresAt);
        return new L4CapabilityToken(id, digest, TrustedAuthorizationVerifier.L4_AUDIENCE,
                operator, proposal.agentIdentity(), proposal.governanceContext().deploymentEnvironment(),
                proposal.governanceContext().governanceSubjectSha256(),
                proposal.proposalId(), proposal.proposalSha256(), proposal.policyId(),
                proposal.policyVersion(), proposal.policyArtifactSha256(), reservation, receipt, context,
                issuedAt, expiresAt);
    }

    public void requireMatches(
            String operator,
            TradeProposal proposal,
            BoundedAutonomyPolicy policy,
            DailyLossSnapshot lossSnapshot) {
        if (proposal == null || policy == null || lossSnapshot == null) {
            throw new IllegalArgumentException("proposal, policy and lossSnapshot are required");
        }
        if (!TrustedAuthorizationVerifier.L4_AUDIENCE.equals(audience)) {
            throw violation("CAPABILITY_AUDIENCE_MISMATCH", "Capability audience is not L4 bounded autonomy");
        }
        if (!requestingOperator.equals(AutonomyValues.text(operator, "operator"))) {
            throw violation("CAPABILITY_OPERATOR_MISMATCH", "Capability operator does not match");
        }
        if (!requestingAgentIdentity.equals(proposal.agentIdentity())) {
            throw violation("CAPABILITY_AGENT_IDENTITY_MISMATCH",
                    "Capability does not bind the requesting Agent workload");
        }
        if (!deploymentEnvironment.equals(proposal.governanceContext().deploymentEnvironment())
                || !governanceSubjectSha256.equals(
                proposal.governanceContext().governanceSubjectSha256())
                || !policy.governanceContext().equals(proposal.governanceContext())) {
            throw violation("CAPABILITY_GOVERNANCE_CONTEXT_MISMATCH",
                    "Capability environment or governance subject does not match");
        }
        if (!proposalId.equals(proposal.proposalId()) || !proposalSha256.equals(proposal.proposalSha256())) {
            throw violation("CAPABILITY_PROPOSAL_MISMATCH", "Capability does not bind the full proposal");
        }
        if (!policyId.equals(policy.policyId()) || !policyVersion.equals(policy.policyVersion())
                || !policyArtifactSha256.equals(policy.policyArtifactSha256())
                || !policyId.equals(proposal.policyId()) || !policyVersion.equals(proposal.policyVersion())
                || !policyArtifactSha256.equals(proposal.policyArtifactSha256())) {
            throw violation("CAPABILITY_POLICY_MISMATCH", "Capability does not bind the exact policy artifact");
        }
        if (!contextSha256.equals(lossSnapshot.snapshotSha256())) {
            throw violation("CAPABILITY_CONTEXT_MISMATCH", "Capability does not bind the daily-loss facts");
        }
    }

    /** 显式 TTL 校验；持久 store 仍必须使用数据库时间在同一 CAS 中重复验证。 */
    public void requireActiveAt(Instant now) {
        if (now == null || now.isBefore(issuedAt) || !now.isBefore(expiresAt)) {
            throw violation("CAPABILITY_EXPIRED", "Capability is not active at the verification instant");
        }
    }

    private static String hash(
            String tokenId,
            String audience,
            String operator,
            String agentIdentity,
            String deploymentEnvironment,
            String governanceSubjectSha256,
            String proposalId,
            String proposalSha256,
            String policyId,
            String policyVersion,
            String policyArtifactSha256,
            String riskReservationSha256,
            String authorizationReceiptSha256,
            String contextSha256,
            Instant issuedAt,
            Instant expiresAt) {
        if (issuedAt == null || expiresAt == null) throw new IllegalArgumentException("token timestamps are required");
        return AutonomyValues.sha256(sink -> sink
                .value(HASH_DOMAIN)
                .value(AutonomyValues.text(tokenId, "tokenId"))
                .value(AutonomyValues.text(audience, "audience"))
                .value(AutonomyValues.text(operator, "operator"))
                .value(AutonomyValues.text(agentIdentity, "agentIdentity"))
                .value(AutonomyValues.text(deploymentEnvironment, "deploymentEnvironment")
                        .toUpperCase(java.util.Locale.ROOT))
                .value(AutonomyValues.digest(governanceSubjectSha256, "governanceSubjectSha256"))
                .value(AutonomyValues.text(proposalId, "proposalId"))
                .value(AutonomyValues.digest(proposalSha256, "proposalSha256"))
                .value(AutonomyValues.text(policyId, "policyId"))
                .value(AutonomyValues.text(policyVersion, "policyVersion"))
                .value(AutonomyValues.digest(policyArtifactSha256, "policyArtifactSha256"))
                .value(AutonomyValues.digest(riskReservationSha256, "riskReservationSha256"))
                .value(AutonomyValues.digest(authorizationReceiptSha256, "authorizationReceiptSha256"))
                .value(AutonomyValues.digest(contextSha256, "contextSha256"))
                .value(issuedAt)
                .value(expiresAt));
    }

    private static AgentAutonomyViolationException violation(String code, String message) {
        return new AgentAutonomyViolationException(code, message);
    }
}
