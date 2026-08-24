package com.tj.crypto.autonomy.agent;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * L4 的仓库内确定性边界：只对预批准的选择/调整签发并消费一次性能力审计回执。
 *
 * <p>没有自我晋级、权限变更、政策变更、硬风控变更、密钥、提款、订单或 gateway API。
 * 默认 verifier 拒绝所有请求。
 */
public final class L4BoundedAutonomyService {

    private final TrustedAuthorizationVerifier verifier;
    private final ProposalRevocationStore revocations;
    private final AuthorizationTokenStore authorizationTokens;
    private final CapabilityTokenStore capabilities;
    private final Clock clock;
    private final Supplier<String> authorizationUseIds;
    private final Supplier<String> capabilityIds;

    public L4BoundedAutonomyService() {
        this(new RejectingTrustedAuthorizationVerifier(), new RejectingProposalRevocationStore(),
                new RejectingAuthorizationTokenStore(), new RejectingCapabilityTokenStore(),
                Clock.systemUTC(), () -> UUID.randomUUID().toString(),
                () -> UUID.randomUUID().toString());
    }

    public L4BoundedAutonomyService(
            TrustedAuthorizationVerifier verifier,
            ProposalRevocationStore revocations,
            AuthorizationTokenStore authorizationTokens,
            CapabilityTokenStore capabilities,
            Clock clock,
            Supplier<String> authorizationUseIds,
            Supplier<String> capabilityIds) {
        if (verifier == null || revocations == null || authorizationTokens == null
                || capabilities == null || clock == null
                || authorizationUseIds == null || capabilityIds == null) {
            throw new IllegalArgumentException("verifier, revocations, clock and id suppliers are required");
        }
        this.verifier = verifier;
        this.revocations = revocations;
        this.authorizationTokens = authorizationTokens;
        this.capabilities = capabilities;
        this.clock = clock;
        this.authorizationUseIds = authorizationUseIds;
        this.capabilityIds = capabilityIds;
    }

    public L4CapabilityToken authorize(
            TradeProposal proposal,
            String requestingOperator,
            BoundedAutonomyPolicy policy,
            DailyLossSnapshot lossSnapshot,
            AuthorizationReference policyGrant) {
        if (proposal == null || policy == null || lossSnapshot == null || policyGrant == null) {
            throw new IllegalArgumentException("proposal, policy, lossSnapshot and policyGrant are required");
        }
        String operator = AutonomyValues.text(requestingOperator, "requestingOperator");
        Instant now = clock.instant();
        requireNotRevoked(proposal.proposalId());
        policy.requireAllows(proposal, lossSnapshot, now);

        String useId = AutonomyValues.text(authorizationUseIds.get(), "authorizationUseId");
        AuthorizationVerificationRequest request = new AuthorizationVerificationRequest(
                useId, policyGrant, AuthorizationKind.PREAPPROVED_L4_POLICY_GRANT,
                TrustedAuthorizationVerifier.L4_AUDIENCE, operator, proposal.agentIdentity(),
                proposal.governanceContext().deploymentEnvironment(),
                proposal.governanceContext().governanceSubjectSha256(),
                proposal.proposalId(), proposal.proposalSha256(), proposal.policyId(),
                proposal.policyVersion(), proposal.policyArtifactSha256(),
                lossSnapshot.snapshotSha256(), proposal.expiresAt());
        VerifiedAuthorization verified = verifier.verifyAndConsume(request);
        if (verified == null) {
            throw violation("AUTHORIZATION_EMPTY_RECEIPT", "Trusted verifier returned no receipt");
        }
        verified.requireExactMatch(request);
        verified.requireIndependentFrom(request);
        if (verified.principalKind() != AuthorizationPrincipalKind.POLICY_AUTHORITY) {
            throw violation("L4_POLICY_AUTHORITY_REQUIRED", "L4 requires an authenticated policy authority");
        }
        if (!now.isBefore(verified.expiresAt())) {
            throw violation("AUTHORIZATION_EXPIRED", "L4 policy grant has expired");
        }

        // 外部调用后重验撤销、时效、风险快照和全部边界，避免 verifier 延迟造成 TOCTOU 放行。
        Instant recheckedAt = clock.instant();
        requireNotRevoked(proposal.proposalId());
        policy.requireAllows(proposal, lossSnapshot, recheckedAt);
        authorizationTokens.claim(AuthorizationTokenClaim.from(request, verified, recheckedAt));
        Instant capabilityExpiresAt = java.util.stream.Stream.of(
                        proposal.expiresAt(), policy.validUntil(), verified.expiresAt(),
                        recheckedAt.plus(policy.maxCapabilityTtl()))
                .min(Comparator.naturalOrder()).orElseThrow();
        if (!capabilityExpiresAt.isAfter(recheckedAt)) {
            throw violation("CAPABILITY_TTL_INVALID", "No positive capability lifetime remains");
        }
        CapabilityBudgetReservation reservation = CapabilityBudgetReservation.create(
                proposal, lossSnapshot, policy, recheckedAt, capabilityExpiresAt);
        L4CapabilityToken token = L4CapabilityToken.issue(
                AutonomyValues.text(capabilityIds.get(), "capabilityId"), operator, proposal,
                reservation.reservationSha256(), verified.verificationReceiptSha256(),
                lossSnapshot.snapshotSha256(),
                recheckedAt, capabilityExpiresAt);
        capabilities.issue(token, reservation);
        return token;
    }

    public CapabilityConsumption consumeCapability(
            L4CapabilityToken token,
            CapabilityUseRequest useRequest,
            TradeProposal proposal,
            BoundedAutonomyPolicy policy,
            DailyLossSnapshot lossSnapshot) {
        if (token == null || useRequest == null || proposal == null
                || policy == null || lossSnapshot == null) {
            throw new IllegalArgumentException(
                    "token, useRequest, proposal, policy and lossSnapshot are required");
        }
        Instant now = clock.instant();
        requireNotRevoked(proposal.proposalId());
        token.requireActiveAt(now);
        useRequest.requireActiveAt(now);
        useRequest.requireMatches(token, proposal, policy);
        token.requireMatches(useRequest.requestingOperator(), proposal, policy, lossSnapshot);
        policy.requireAllows(proposal, lossSnapshot, now);
        CapabilityConsumerVerificationRequest consumerRequest =
                CapabilityConsumerVerificationRequest.from(useRequest);
        VerifiedCapabilityConsumer consumer = verifier.verifyCapabilityConsumer(consumerRequest);
        if (consumer == null) {
            throw violation("CAPABILITY_CONSUMER_EMPTY_RECEIPT", "Consumer verifier returned no receipt");
        }
        consumer.requireExactMatch(consumerRequest);
        Instant recheckedAt = clock.instant();
        token.requireActiveAt(recheckedAt);
        useRequest.requireActiveAt(recheckedAt);
        useRequest.requireMatches(token, proposal, policy);
        requireNotRevoked(proposal.proposalId());
        policy.requireAllows(proposal, lossSnapshot, recheckedAt);
        capabilities.consume(token, useRequest, recheckedAt);
        String consumptionReceipt = AutonomyValues.sha256(sink -> sink
                .value("agent-l4-local-consumption-receipt-v2")
                .value(token.tokenSha256())
                .value(useRequest.useRequestSha256())
                .value(consumer.verificationReceiptSha256())
                .value(recheckedAt));
        CapabilityConsumption consumption = new CapabilityConsumption(
                token.tokenId(), useRequest, token.proposalId(), token.proposalSha256(),
                token.policyId(), token.policyVersion(), useRequest.requestingOperator(),
                consumer.verificationReceiptSha256(), recheckedAt, consumptionReceipt);
        // 若撤销与消费并发，消费被烧毁但不会返回可用回执。
        requireNotRevoked(proposal.proposalId());
        Instant completedAt = clock.instant();
        token.requireActiveAt(completedAt);
        useRequest.requireActiveAt(completedAt);
        policy.requireAllows(proposal, lossSnapshot, completedAt);
        return consumption;
    }

    /**
     * @deprecated 自由字符串 useId 无法绑定最终命令、估值、风险与 decision envelope，始终拒绝。
     */
    @Deprecated(forRemoval = false)
    public CapabilityConsumption consumeCapability(
            L4CapabilityToken token,
            String useId,
            String requestingOperator,
            TradeProposal proposal,
            BoundedAutonomyPolicy policy,
            DailyLossSnapshot lossSnapshot) {
        throw violation("UNBOUND_CAPABILITY_USE_FORBIDDEN",
                "String useId is forbidden; a content-addressed CapabilityUseRequest is required");
    }

    public ProposalRevocation revokeProposal(
            String proposalId, String operator, String reason) {
        return revocations.revoke(proposalId, operator, reason, clock.instant());
    }

    public void revokePolicyGrantToken(String tokenId, String reason) {
        authorizationTokens.revoke(tokenId, reason, clock.instant());
    }

    public void revokeCapability(String tokenId, String reason) {
        capabilities.revoke(tokenId, reason, clock.instant());
    }

    private void requireNotRevoked(String proposalId) {
        if (revocations.isRevoked(proposalId)) {
            throw violation("PROPOSAL_REVOKED", "Trade proposal has been revoked");
        }
    }

    private static AgentAutonomyViolationException violation(String code, String message) {
        return new AgentAutonomyViolationException(code, message);
    }
}
