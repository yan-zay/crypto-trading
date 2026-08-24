package com.tj.crypto.autonomy.agent;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

final class AutonomyTestFixtures {

    static final Instant NOW = Instant.parse("2026-08-24T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final String D1 = "1".repeat(64);
    static final String D2 = "2".repeat(64);
    static final String D3 = "3".repeat(64);
    static final String D4 = "4".repeat(64);
    static final String D5 = "5".repeat(64);
    static final String D6 = "6".repeat(64);
    static final String D7 = "7".repeat(64);
    static final String D8 = "8".repeat(64);
    static final String D9 = "9".repeat(64);
    static final String DA = "a".repeat(64);
    static final String DB = "b".repeat(64);

    static final AutonomyGovernanceContext GOVERNANCE =
            new AutonomyGovernanceContext("live-canary", D1);
    static final AgentDecisionLineage LINEAGE = new AgentDecisionLineage(
            "model-v1", D2, D3, "tool-policy-v1", D4, D5, D6);
    static final StrategyRef STRATEGY = new StrategyRef(
            "golden-strategy", "release-7", D7, "assignment-live-a", "3");
    static final AccountRef ACCOUNT = new AccountRef("isolated-subaccount-a");
    static final VenueRef VENUE = new VenueRef("compliant-venue-a");
    static final InstrumentRef INSTRUMENT = new InstrumentRef(
            "ISO-10962", "CRYPTO", "BTCUSDT-PERP", D8);

    private AutonomyTestFixtures() {
    }

    static BoundedAutonomyPolicy policy() {
        return BoundedAutonomyPolicy.create(
                "l4-policy", "v3", GOVERNANCE,
                Set.of(new AutonomyScope(STRATEGY, ACCOUNT, VENUE, INSTRUMENT)),
                Set.of(TradeProposalAction.L4_SELECT_PREAPPROVED,
                        TradeProposalAction.L4_ADJUST_PREAPPROVED),
                List.of(new TradingWindow(DayOfWeek.MONDAY, LocalTime.of(9, 0),
                        LocalTime.of(17, 0), ZoneOffset.UTC)),
                new BigDecimal("1000"), "usd", new BigDecimal("100"),
                new BigDecimal("20"), Duration.ofMinutes(5), Duration.ofSeconds(30),
                Duration.ofSeconds(30), NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)));
    }

    static TradeProposal l4Proposal(BoundedAutonomyPolicy policy) {
        return proposal(TradeProposalAction.L4_SELECT_PREAPPROVED, policy,
                ACCOUNT, INSTRUMENT, new BigDecimal("200"), new BigDecimal("10"),
                NOW.minusSeconds(5), NOW.plusSeconds(90));
    }

    static TradeProposal proposal(
            TradeProposalAction action,
            BoundedAutonomyPolicy policy,
            AccountRef account,
            InstrumentRef instrument,
            BigDecimal notional,
            BigDecimal lossAtStop,
            Instant createdAt,
            Instant expiresAt) {
        return TradeProposal.create(action, GOVERNANCE, "agent-workload-a", LINEAGE,
                STRATEGY, account, VENUE, instrument, TradeDirection.INCREASE_LONG,
                notional, "usd", lossAtStop, policy.policyId(), policy.policyVersion(),
                policy.policyArtifactSha256(), D9, createdAt, expiresAt);
    }

    static TradeProposal l3Proposal() {
        return TradeProposal.create(TradeProposalAction.L3_LIVE_ADVICE, GOVERNANCE,
                "agent-workload-a", LINEAGE, STRATEGY, ACCOUNT, VENUE, INSTRUMENT,
                TradeDirection.INCREASE_LONG, new BigDecimal("100"), "usd",
                new BigDecimal("5"), "l3-review-policy", "v2", DA, DB,
                NOW.minusSeconds(5), NOW.plusSeconds(90));
    }

    static DailyLossSnapshot lossSnapshot() {
        return DailyLossSnapshot.create(ACCOUNT, "usd", LocalDate.of(2026, 8, 24),
                ZoneOffset.UTC, new BigDecimal("20"), new BigDecimal("5"),
                new BigDecimal("100"), NOW.minusSeconds(5), D6,
                RiskSnapshotCompleteness.COMPLETE);
    }

    static CapabilityUseRequest capabilityUse(
            L4CapabilityToken token,
            TradeProposal proposal,
            BoundedAutonomyPolicy policy) {
        return CapabilityUseRequest.create(token, proposal, policy, "operator",
                proposal.agentIdentity(), D1, D2, D3, D4, D5,
                NOW, NOW.plusSeconds(20));
    }

    static VerifiedCapabilityConsumer copyConsumer(
            VerifiedCapabilityConsumer value,
            String executionCandidateSha256,
            String commandSha256,
            String valuationSnapshotSha256,
            String riskBudgetSha256,
            String riskReservationSha256,
            String orderDecisionEnvelopeSha256) {
        return new VerifiedCapabilityConsumer(value.authenticatedOperator(),
                value.authenticatedConsumerIdentity(), value.useRequestSha256(), value.audience(),
                value.capabilityTokenId(), value.capabilityTokenSha256(),
                value.deploymentEnvironment(), value.governanceSubjectSha256(), value.proposalId(),
                value.proposalSha256(), value.policyId(), value.policyVersion(),
                value.policyArtifactSha256(), executionCandidateSha256, commandSha256,
                valuationSnapshotSha256, riskBudgetSha256, riskReservationSha256,
                orderDecisionEnvelopeSha256, value.useCreatedAt(), value.useExpiresAt(),
                value.consumedUseId(), value.verificationReceiptSha256());
    }

    static AuthorizationReference reference(
            AuthorizationKind kind,
            TradeProposal proposal,
            String operator,
            String contextSha256,
            String tokenId) {
        String audience = kind == AuthorizationKind.INDEPENDENT_HUMAN_APPROVAL
                ? TrustedAuthorizationVerifier.L3_AUDIENCE
                : TrustedAuthorizationVerifier.L4_AUDIENCE;
        String principal = kind == AuthorizationKind.INDEPENDENT_HUMAN_APPROVAL
                ? "independent-human-reviewer" : "independent-policy-authority";
        return new AuthorizationReference(tokenId, D5, kind, audience, operator,
                proposal.agentIdentity(), proposal.governanceContext().deploymentEnvironment(),
                proposal.governanceContext().governanceSubjectSha256(), proposal.proposalId(),
                proposal.proposalSha256(), proposal.policyId(), proposal.policyVersion(),
                proposal.policyArtifactSha256(), contextSha256, principal,
                proposal.expiresAt());
    }

    static final class ExactVerifier implements TrustedAuthorizationVerifier {
        private final UnaryOperator<VerifiedAuthorization> authorizationMutation;
        private final UnaryOperator<VerifiedCapabilityConsumer> consumerMutation;

        ExactVerifier() {
            this(UnaryOperator.identity(), UnaryOperator.identity());
        }

        ExactVerifier(UnaryOperator<VerifiedAuthorization> authorizationMutation) {
            this(authorizationMutation, UnaryOperator.identity());
        }

        ExactVerifier(UnaryOperator<VerifiedAuthorization> authorizationMutation,
                      UnaryOperator<VerifiedCapabilityConsumer> consumerMutation) {
            this.authorizationMutation = authorizationMutation;
            this.consumerMutation = consumerMutation;
        }

        @Override
        public VerifiedAuthorization verifyAndConsume(AuthorizationVerificationRequest request) {
            AuthorizationReference ref = request.claimedReference();
            AuthorizationPrincipalKind principalKind = request.expectedKind()
                    == AuthorizationKind.INDEPENDENT_HUMAN_APPROVAL
                    ? AuthorizationPrincipalKind.HUMAN_APPROVER
                    : AuthorizationPrincipalKind.POLICY_AUTHORITY;
            VerifiedAuthorization exact = new VerifiedAuthorization(
                    ref.tokenId(), ref.artifactSha256(), request.expectedKind(),
                    request.expectedAudience(), request.requestingOperator(), request.agentIdentity(),
                    request.deploymentEnvironment(), request.governanceSubjectSha256(),
                    request.proposalId(), request.proposalSha256(), request.policyId(),
                    request.policyVersion(), request.policyArtifactSha256(), request.contextSha256(),
                    ref.claimedAuthorizingPrincipal(), principalKind, request.useId(),
                    ref.expiresAt(), D4);
            return authorizationMutation.apply(exact);
        }

        @Override
        public VerifiedCapabilityConsumer verifyCapabilityConsumer(
                CapabilityConsumerVerificationRequest request) {
            VerifiedCapabilityConsumer exact = new VerifiedCapabilityConsumer(
                    request.claimedOperator(), request.claimedConsumerIdentity(),
                    request.useRequestSha256(), request.audience(), request.capabilityTokenId(),
                    request.capabilityTokenSha256(), request.deploymentEnvironment(),
                    request.governanceSubjectSha256(), request.proposalId(), request.proposalSha256(),
                    request.policyId(), request.policyVersion(), request.policyArtifactSha256(),
                    request.executionCandidateSha256(), request.commandSha256(),
                    request.valuationSnapshotSha256(), request.riskBudgetSha256(),
                    request.riskReservationSha256(), request.orderDecisionEnvelopeSha256(),
                    request.useCreatedAt(), request.useExpiresAt(), request.useId(), D3);
            return consumerMutation.apply(exact);
        }
    }

    static final class InMemoryAuthorizationTokenStore implements AuthorizationTokenStore {
        private final Map<String, String> states = new HashMap<>();

        @Override
        public synchronized void claim(AuthorizationTokenClaim claim) {
            if (states.putIfAbsent(claim.tokenId(), "CONSUMED:" + claim.useId()
                    + ":" + claim.proposalSha256()) != null) {
                throw new AgentAutonomyViolationException("AUTHORIZATION_REPLAY", "already used");
            }
        }

        @Override
        public synchronized void revoke(String tokenId, String reason, Instant revokedAt) {
            if (states.putIfAbsent(tokenId, "REVOKED") != null) {
                throw new AgentAutonomyViolationException("AUTHORIZATION_ALREADY_CONSUMED", "already used");
            }
        }
    }

    static final class InMemoryRevocationStore implements ProposalRevocationStore {
        private final Map<String, ProposalRevocation> revocations = new HashMap<>();

        @Override
        public synchronized ProposalRevocation revoke(
                String proposalId, String operator, String reason, Instant revokedAt) {
            return revocations.computeIfAbsent(proposalId,
                    ignored -> new ProposalRevocation(proposalId, operator, reason, revokedAt));
        }

        @Override
        public synchronized boolean isRevoked(String proposalId) {
            return revocations.containsKey(proposalId);
        }
    }

    static final class InMemoryCapabilityTokenStore implements CapabilityTokenStore {
        private final Map<String, State> states = new HashMap<>();
        private final Set<String> consumedRiskSnapshotHeads = new HashSet<>();
        private CapabilityBudgetReservation lastReservation;

        @Override
        public synchronized void issue(
                L4CapabilityToken token, CapabilityBudgetReservation reservation) {
            if (!token.riskReservationSha256().equals(reservation.reservationSha256())) {
                throw new AgentAutonomyViolationException("RESERVATION_BINDING_MISMATCH", "wrong reservation");
            }
            if (!consumedRiskSnapshotHeads.add(reservation.riskSnapshotSha256())) {
                throw new AgentAutonomyViolationException(
                        "RISK_SNAPSHOT_HEAD_CHANGED", "stale risk snapshot head");
            }
            if (reservation.expectedPendingNotional().add(reservation.notionalToReserve())
                    .compareTo(reservation.maxNotional()) > 0
                    || reservation.expectedRealizedLoss().add(reservation.expectedPendingLoss())
                    .add(reservation.lossAtStopToReserve()).compareTo(reservation.maxDailyLoss()) > 0) {
                throw new AgentAutonomyViolationException("ATOMIC_BUDGET_EXCEEDED", "budget exceeded");
            }
            if (states.putIfAbsent(token.tokenId(), new State(token, false, false)) != null) {
                throw new AgentAutonomyViolationException("CAPABILITY_ID_COLLISION", "duplicate token");
            }
            lastReservation = reservation;
        }

        @Override
        public synchronized void consume(
                L4CapabilityToken token, CapabilityUseRequest useRequest, Instant consumedAt) {
            State state = states.get(token.tokenId());
            if (state == null || !state.token.equals(token)) {
                throw new AgentAutonomyViolationException("CAPABILITY_UNKNOWN", "unknown token");
            }
            if (state.revoked) throw new AgentAutonomyViolationException("CAPABILITY_REVOKED", "revoked");
            if (state.consumed) throw new AgentAutonomyViolationException("CAPABILITY_REPLAY", "replay");
            if (!useRequest.capabilityTokenId().equals(token.tokenId())
                    || !useRequest.capabilityTokenSha256().equals(token.tokenSha256())
                    || !useRequest.proposalSha256().equals(token.proposalSha256())
                    || !useRequest.policyArtifactSha256().equals(token.policyArtifactSha256())
                    || !useRequest.riskReservationSha256().equals(token.riskReservationSha256())) {
                throw new AgentAutonomyViolationException(
                        "CAPABILITY_USE_STORE_BINDING_MISMATCH", "use request binding mismatch");
            }
            token.requireActiveAt(consumedAt);
            useRequest.requireActiveAt(consumedAt);
            states.put(token.tokenId(), new State(token, true, false));
        }

        @Override
        public synchronized void revoke(String tokenId, String reason, Instant revokedAt) {
            State state = states.get(tokenId);
            if (state == null) throw new AgentAutonomyViolationException("CAPABILITY_UNKNOWN", "unknown");
            if (state.consumed) {
                throw new AgentAutonomyViolationException("CAPABILITY_ALREADY_CONSUMED", "consumed");
            }
            states.put(tokenId, new State(state.token, false, true));
        }

        CapabilityBudgetReservation lastReservation() {
            return lastReservation;
        }

        private record State(L4CapabilityToken token, boolean consumed, boolean revoked) {
        }
    }
}
