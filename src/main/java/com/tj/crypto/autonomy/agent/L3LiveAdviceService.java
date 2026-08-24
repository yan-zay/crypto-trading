package com.tj.crypto.autonomy.agent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * L3 唯一出口：把不可执行 {@link TradeProposal} 变为经独立人类可信审批的建议回执。
 *
 * <p>本服务不依赖 OMS、签名器、私有 API 或交易网关。默认构造器使用拒绝型 verifier。
 */
public final class L3LiveAdviceService {

    public static final Duration DEFAULT_MAX_PROPOSAL_TTL = Duration.ofMinutes(5);
    public static final Duration ABSOLUTE_MAX_PROPOSAL_TTL = Duration.ofMinutes(15);

    private final TrustedAuthorizationVerifier verifier;
    private final ProposalRevocationStore revocations;
    private final AuthorizationTokenStore authorizationTokens;
    private final Clock clock;
    private final Duration maxProposalTtl;
    private final Supplier<String> useIds;

    public L3LiveAdviceService() {
        this(new RejectingTrustedAuthorizationVerifier(), new RejectingProposalRevocationStore(),
                new RejectingAuthorizationTokenStore(),
                Clock.systemUTC(), DEFAULT_MAX_PROPOSAL_TTL, () -> UUID.randomUUID().toString());
    }

    public L3LiveAdviceService(
            TrustedAuthorizationVerifier verifier,
            ProposalRevocationStore revocations,
            AuthorizationTokenStore authorizationTokens,
            Clock clock,
            Duration maxProposalTtl,
            Supplier<String> useIds) {
        if (verifier == null || revocations == null || authorizationTokens == null
                || clock == null || maxProposalTtl == null
                || useIds == null || maxProposalTtl.isNegative() || maxProposalTtl.isZero()) {
            throw new IllegalArgumentException("valid verifier, revocations, clock, TTL and useIds are required");
        }
        if (maxProposalTtl.compareTo(ABSOLUTE_MAX_PROPOSAL_TTL) > 0) {
            throw new IllegalArgumentException("L3 maxProposalTtl exceeds the absolute 15-minute ceiling");
        }
        this.verifier = verifier;
        this.revocations = revocations;
        this.authorizationTokens = authorizationTokens;
        this.clock = clock;
        this.maxProposalTtl = maxProposalTtl;
        this.useIds = useIds;
    }

    public ApprovedLiveAdvice approve(
            TradeProposal proposal,
            String requestingOperator,
            AuthorizationReference humanApproval) {
        if (proposal == null || humanApproval == null) {
            throw new IllegalArgumentException("proposal and humanApproval are required");
        }
        String operator = AutonomyValues.text(requestingOperator, "requestingOperator");
        Instant now = clock.instant();
        requireNotRevoked(proposal.proposalId());
        validateProposal(proposal, now);

        String useId = AutonomyValues.text(useIds.get(), "useId");
        AuthorizationVerificationRequest request = new AuthorizationVerificationRequest(
                useId, humanApproval, AuthorizationKind.INDEPENDENT_HUMAN_APPROVAL,
                TrustedAuthorizationVerifier.L3_AUDIENCE, operator, proposal.agentIdentity(),
                proposal.governanceContext().deploymentEnvironment(),
                proposal.governanceContext().governanceSubjectSha256(),
                proposal.proposalId(), proposal.proposalSha256(), proposal.policyId(),
                proposal.policyVersion(), proposal.policyArtifactSha256(),
                AuthorizationReference.EMPTY_CONTEXT_SHA256, proposal.expiresAt());

        VerifiedAuthorization verified = verifier.verifyAndConsume(request);
        if (verified == null) {
            throw violation("AUTHORIZATION_EMPTY_RECEIPT", "Trusted verifier returned no receipt");
        }
        verified.requireExactMatch(request);
        verified.requireIndependentFrom(request);
        if (verified.principalKind() != AuthorizationPrincipalKind.HUMAN_APPROVER) {
            throw violation("HUMAN_APPROVAL_REQUIRED", "L3 requires an independently authenticated human approver");
        }
        Instant recheckedAt = clock.instant();
        if (!recheckedAt.isBefore(verified.expiresAt())
                || !recheckedAt.isBefore(proposal.expiresAt())) {
            throw violation("AUTHORIZATION_EXPIRED", "Human approval has expired");
        }
        requireNotRevoked(proposal.proposalId());
        authorizationTokens.claim(AuthorizationTokenClaim.from(request, verified, recheckedAt));
        return new ApprovedLiveAdvice(proposal, operator, verified.authenticatedAuthorizingPrincipal(),
                verified.verificationReceiptSha256(), recheckedAt, proposal.expiresAt());
    }

    public ProposalRevocation revokeProposal(
            String proposalId, String operator, String reason) {
        return revocations.revoke(proposalId, operator, reason, clock.instant());
    }

    public void revokeApprovalToken(String approvalTokenId, String reason) {
        authorizationTokens.revoke(approvalTokenId, reason, clock.instant());
    }

    private void validateProposal(TradeProposal proposal, Instant now) {
        if (proposal.action() != TradeProposalAction.L3_LIVE_ADVICE) {
            throw violation("L3_ACTION_NOT_ALLOWED", "L3 can only produce live advice");
        }
        Duration ttl = Duration.between(proposal.createdAt(), proposal.expiresAt());
        if (proposal.createdAt().isAfter(now) || !now.isBefore(proposal.expiresAt())
                || ttl.compareTo(maxProposalTtl) > 0) {
            throw violation("L3_PROPOSAL_TTL_INVALID", "L3 proposal is future-dated, expired or exceeds short TTL");
        }
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
