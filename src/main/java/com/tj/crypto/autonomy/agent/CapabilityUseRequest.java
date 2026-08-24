package com.tj.crypto.autonomy.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * 内容寻址的 L4 capability 单次消费请求。
 *
 * <p>它只绑定确定性内核已生成的候选与事实摘要，不包含订单、签名或 gateway 类型。
 * {@code useId} 与 {@code useRequestSha256} 必须同时等于完整规范化负载的 SHA-256，调用方
 * 不能自由选择 useId。
 */
public record CapabilityUseRequest(
        String useId,
        String useRequestSha256,
        String audience,
        String requestingOperator,
        String consumerIdentity,
        String capabilityTokenId,
        String capabilityTokenSha256,
        String deploymentEnvironment,
        String governanceSubjectSha256,
        String proposalId,
        String proposalSha256,
        String policyId,
        String policyVersion,
        String policyArtifactSha256,
        String executionCandidateSha256,
        String commandSha256,
        String valuationSnapshotSha256,
        String riskBudgetSha256,
        String riskReservationSha256,
        String orderDecisionEnvelopeSha256,
        Instant createdAt,
        Instant expiresAt) {

    private static final String HASH_DOMAIN = "agent-l4-capability-use-v1";
    public static final Duration ABSOLUTE_MAX_TTL = Duration.ofSeconds(30);

    public CapabilityUseRequest {
        useId = AutonomyValues.digest(useId, "useId");
        useRequestSha256 = AutonomyValues.digest(useRequestSha256, "useRequestSha256");
        audience = AutonomyValues.text(audience, "audience");
        requestingOperator = AutonomyValues.text(requestingOperator, "requestingOperator");
        consumerIdentity = AutonomyValues.text(consumerIdentity, "consumerIdentity");
        capabilityTokenId = AutonomyValues.text(capabilityTokenId, "capabilityTokenId");
        capabilityTokenSha256 = AutonomyValues.digest(capabilityTokenSha256, "capabilityTokenSha256");
        deploymentEnvironment = AutonomyValues.text(
                deploymentEnvironment, "deploymentEnvironment").toUpperCase(Locale.ROOT);
        governanceSubjectSha256 = AutonomyValues.digest(
                governanceSubjectSha256, "governanceSubjectSha256");
        proposalId = AutonomyValues.text(proposalId, "proposalId");
        proposalSha256 = AutonomyValues.digest(proposalSha256, "proposalSha256");
        policyId = AutonomyValues.text(policyId, "policyId");
        policyVersion = AutonomyValues.text(policyVersion, "policyVersion");
        policyArtifactSha256 = AutonomyValues.digest(policyArtifactSha256, "policyArtifactSha256");
        executionCandidateSha256 = AutonomyValues.digest(
                executionCandidateSha256, "executionCandidateSha256");
        commandSha256 = AutonomyValues.digest(commandSha256, "commandSha256");
        valuationSnapshotSha256 = AutonomyValues.digest(
                valuationSnapshotSha256, "valuationSnapshotSha256");
        riskBudgetSha256 = AutonomyValues.digest(riskBudgetSha256, "riskBudgetSha256");
        riskReservationSha256 = AutonomyValues.digest(
                riskReservationSha256, "riskReservationSha256");
        orderDecisionEnvelopeSha256 = AutonomyValues.digest(
                orderDecisionEnvelopeSha256, "orderDecisionEnvelopeSha256");
        validateTimestamps(createdAt, expiresAt);
        String expected = hash(audience, requestingOperator, consumerIdentity, capabilityTokenId,
                capabilityTokenSha256, deploymentEnvironment, governanceSubjectSha256,
                proposalId, proposalSha256, policyId, policyVersion, policyArtifactSha256,
                executionCandidateSha256, commandSha256, valuationSnapshotSha256,
                riskBudgetSha256, riskReservationSha256, orderDecisionEnvelopeSha256,
                createdAt, expiresAt);
        if (!expected.equals(useId) || !expected.equals(useRequestSha256)) {
            throw new IllegalArgumentException(
                    "useId and useRequestSha256 must address the complete capability use payload");
        }
    }

    public static CapabilityUseRequest create(
            L4CapabilityToken token,
            TradeProposal proposal,
            BoundedAutonomyPolicy policy,
            String requestingOperator,
            String consumerIdentity,
            String executionCandidateSha256,
            String commandSha256,
            String valuationSnapshotSha256,
            String riskBudgetSha256,
            String orderDecisionEnvelopeSha256,
            Instant createdAt,
            Instant expiresAt) {
        if (token == null || proposal == null || policy == null) {
            throw new IllegalArgumentException("token, proposal and policy are required");
        }
        validateTimestamps(createdAt, expiresAt);
        if (createdAt.isBefore(token.issuedAt()) || expiresAt.isAfter(token.expiresAt())
                || expiresAt.isAfter(proposal.expiresAt())) {
            throw new IllegalArgumentException("capability use lifetime must be inside token and proposal lifetime");
        }
        String operator = AutonomyValues.text(requestingOperator, "requestingOperator");
        String consumer = AutonomyValues.text(consumerIdentity, "consumerIdentity");
        String candidate = AutonomyValues.digest(executionCandidateSha256, "executionCandidateSha256");
        String command = AutonomyValues.digest(commandSha256, "commandSha256");
        String valuation = AutonomyValues.digest(valuationSnapshotSha256, "valuationSnapshotSha256");
        String riskBudget = AutonomyValues.digest(riskBudgetSha256, "riskBudgetSha256");
        String envelope = AutonomyValues.digest(
                orderDecisionEnvelopeSha256, "orderDecisionEnvelopeSha256");
        String digest = hash(token.audience(), operator, consumer, token.tokenId(), token.tokenSha256(),
                proposal.governanceContext().deploymentEnvironment(),
                proposal.governanceContext().governanceSubjectSha256(), proposal.proposalId(),
                proposal.proposalSha256(), policy.policyId(), policy.policyVersion(),
                policy.policyArtifactSha256(), candidate, command, valuation, riskBudget,
                token.riskReservationSha256(), envelope, createdAt, expiresAt);
        return new CapabilityUseRequest(digest, digest, token.audience(), operator, consumer,
                token.tokenId(), token.tokenSha256(),
                proposal.governanceContext().deploymentEnvironment(),
                proposal.governanceContext().governanceSubjectSha256(), proposal.proposalId(),
                proposal.proposalSha256(), policy.policyId(), policy.policyVersion(),
                policy.policyArtifactSha256(), candidate, command, valuation, riskBudget,
                token.riskReservationSha256(), envelope, createdAt, expiresAt);
    }

    public void requireMatches(
            L4CapabilityToken token,
            TradeProposal proposal,
            BoundedAutonomyPolicy policy) {
        if (token == null || proposal == null || policy == null) {
            throw new IllegalArgumentException("token, proposal and policy are required");
        }
        if (!audience.equals(token.audience())
                || !capabilityTokenId.equals(token.tokenId())
                || !capabilityTokenSha256.equals(token.tokenSha256())) {
            throw violation("CAPABILITY_USE_TOKEN_MISMATCH", "Use request binds another capability token");
        }
        if (!requestingOperator.equals(token.requestingOperator())
                || !consumerIdentity.equals(token.requestingAgentIdentity())) {
            throw violation("CAPABILITY_USE_CONSUMER_MISMATCH",
                    "Use request operator or consumer does not match the token");
        }
        if (!deploymentEnvironment.equals(token.deploymentEnvironment())
                || !governanceSubjectSha256.equals(token.governanceSubjectSha256())) {
            throw violation("CAPABILITY_USE_GOVERNANCE_CONTEXT_MISMATCH",
                    "Use request environment or governance subject does not match the token");
        }
        if (!proposalId.equals(proposal.proposalId())
                || !proposalSha256.equals(proposal.proposalSha256())) {
            throw violation("CAPABILITY_USE_PROPOSAL_MISMATCH", "Use request binds another proposal");
        }
        if (!policyId.equals(policy.policyId()) || !policyVersion.equals(policy.policyVersion())
                || !policyArtifactSha256.equals(policy.policyArtifactSha256())) {
            throw violation("CAPABILITY_USE_POLICY_MISMATCH", "Use request binds another policy");
        }
        if (!riskReservationSha256.equals(token.riskReservationSha256())) {
            throw violation("CAPABILITY_USE_RESERVATION_MISMATCH",
                    "Use request binds another risk reservation");
        }
        if (createdAt.isBefore(token.issuedAt()) || expiresAt.isAfter(token.expiresAt())
                || expiresAt.isAfter(proposal.expiresAt())) {
            throw violation("CAPABILITY_USE_TTL_OUTSIDE_PARENT", "Use lifetime exceeds token or proposal");
        }
    }

    public void requireActiveAt(Instant now) {
        if (now == null || now.isBefore(createdAt) || !now.isBefore(expiresAt)) {
            throw violation("CAPABILITY_USE_EXPIRED", "Capability use request is not active");
        }
    }

    private static void validateTimestamps(Instant createdAt, Instant expiresAt) {
        if (createdAt == null || expiresAt == null || !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("capability use timestamps must define a positive lifetime");
        }
        if (Duration.between(createdAt, expiresAt).compareTo(ABSOLUTE_MAX_TTL) > 0) {
            throw new IllegalArgumentException("capability use TTL exceeds the absolute 30-second ceiling");
        }
    }

    private static String hash(
            String audience,
            String operator,
            String consumer,
            String tokenId,
            String tokenSha256,
            String environment,
            String governanceSubjectSha256,
            String proposalId,
            String proposalSha256,
            String policyId,
            String policyVersion,
            String policyArtifactSha256,
            String executionCandidateSha256,
            String commandSha256,
            String valuationSnapshotSha256,
            String riskBudgetSha256,
            String riskReservationSha256,
            String orderDecisionEnvelopeSha256,
            Instant createdAt,
            Instant expiresAt) {
        return AutonomyValues.sha256(sink -> sink
                .value(HASH_DOMAIN)
                .value(AutonomyValues.text(audience, "audience"))
                .value(AutonomyValues.text(operator, "operator"))
                .value(AutonomyValues.text(consumer, "consumer"))
                .value(AutonomyValues.text(tokenId, "tokenId"))
                .value(AutonomyValues.digest(tokenSha256, "tokenSha256"))
                .value(AutonomyValues.text(environment, "environment").toUpperCase(Locale.ROOT))
                .value(AutonomyValues.digest(governanceSubjectSha256, "governanceSubjectSha256"))
                .value(AutonomyValues.text(proposalId, "proposalId"))
                .value(AutonomyValues.digest(proposalSha256, "proposalSha256"))
                .value(AutonomyValues.text(policyId, "policyId"))
                .value(AutonomyValues.text(policyVersion, "policyVersion"))
                .value(AutonomyValues.digest(policyArtifactSha256, "policyArtifactSha256"))
                .value(AutonomyValues.digest(executionCandidateSha256, "executionCandidateSha256"))
                .value(AutonomyValues.digest(commandSha256, "commandSha256"))
                .value(AutonomyValues.digest(valuationSnapshotSha256, "valuationSnapshotSha256"))
                .value(AutonomyValues.digest(riskBudgetSha256, "riskBudgetSha256"))
                .value(AutonomyValues.digest(riskReservationSha256, "riskReservationSha256"))
                .value(AutonomyValues.digest(orderDecisionEnvelopeSha256, "orderDecisionEnvelopeSha256"))
                .value(createdAt)
                .value(expiresAt));
    }

    private static AgentAutonomyViolationException violation(String code, String message) {
        return new AgentAutonomyViolationException(code, message);
    }
}
