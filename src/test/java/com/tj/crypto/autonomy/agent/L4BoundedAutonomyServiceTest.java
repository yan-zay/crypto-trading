package com.tj.crypto.autonomy.agent;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static com.tj.crypto.autonomy.agent.AutonomyTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class L4BoundedAutonomyServiceTest {

    @Test
    void exactPreapprovedScopeIssuesShortTokenAndConsumesItOnce() {
        BoundedAutonomyPolicy policy = policy();
        TradeProposal proposal = l4Proposal(policy);
        DailyLossSnapshot risk = lossSnapshot();
        InMemoryCapabilityTokenStore capabilities = new InMemoryCapabilityTokenStore();
        L4BoundedAutonomyService service = service(capabilities);

        L4CapabilityToken token = service.authorize(proposal, "operator", policy, risk,
                reference(AuthorizationKind.PREAPPROVED_L4_POLICY_GRANT, proposal, "operator",
                        risk.snapshotSha256(), "policy-grant-1"));
        CapabilityUseRequest use = capabilityUse(token, proposal, policy);
        CapabilityConsumption consumption = service.consumeCapability(
                token, use, proposal, policy, risk);

        assertThat(token.proposalSha256()).isEqualTo(proposal.proposalSha256());
        assertThat(token.policyArtifactSha256()).isEqualTo(policy.policyArtifactSha256());
        assertThat(token.contextSha256()).isEqualTo(risk.snapshotSha256());
        assertThat(token.requestingAgentIdentity()).isEqualTo(proposal.agentIdentity());
        assertThat(Duration.between(token.issuedAt(), token.expiresAt()))
                .isLessThanOrEqualTo(policy.maxCapabilityTtl());
        assertThat(capabilities.lastReservation().reservationSha256())
                .isEqualTo(token.riskReservationSha256());
        assertThat(consumption.proposalSha256()).isEqualTo(proposal.proposalSha256());
        assertThat(consumption.useId()).isEqualTo(use.useRequestSha256());
        assertThat(consumption.commandSha256()).isEqualTo(use.commandSha256());
        assertThat(consumption.valuationSnapshotSha256()).isEqualTo(use.valuationSnapshotSha256());
        assertThatThrownBy(() -> service.consumeCapability(
                token, use, proposal, policy, risk))
                .isInstanceOf(AgentAutonomyViolationException.class)
                .extracting("reasonCode").isEqualTo("CAPABILITY_REPLAY");
    }

    @Test
    void defaultServiceRejectsBecauseDurablePortsAreNotWired() {
        BoundedAutonomyPolicy policy = policy();
        TradeProposal proposal = l4Proposal(policy);
        DailyLossSnapshot risk = lossSnapshot();

        assertThatThrownBy(() -> new L4BoundedAutonomyService().authorize(
                proposal, "operator", policy, risk,
                reference(AuthorizationKind.PREAPPROVED_L4_POLICY_GRANT, proposal, "operator",
                        risk.snapshotSha256(), "grant")))
                .isInstanceOf(AgentAutonomyViolationException.class)
                .extracting("reasonCode").isEqualTo("PROPOSAL_REVOCATION_STORE_NOT_WIRED");
    }

    @Test
    void rejectsScopeNotionalLossTtlWindowAndIncompleteOrStaleRiskFacts() {
        BoundedAutonomyPolicy policy = policy();
        DailyLossSnapshot risk = lossSnapshot();

        TradeProposal wrongAccount = proposal(TradeProposalAction.L4_SELECT_PREAPPROVED, policy,
                new AccountRef("other-account"), INSTRUMENT, BigDecimal.TEN, BigDecimal.ONE,
                NOW.minusSeconds(5), NOW.plusSeconds(30));
        assertReason(() -> policy.requireAllows(wrongAccount, risk, NOW), "L4_SCOPE_NOT_ALLOWED");

        TradeProposal tooLarge = proposal(TradeProposalAction.L4_SELECT_PREAPPROVED, policy,
                ACCOUNT, INSTRUMENT, new BigDecimal("1001"), BigDecimal.ONE,
                NOW.minusSeconds(5), NOW.plusSeconds(30));
        assertReason(() -> policy.requireAllows(tooLarge, risk, NOW), "L4_MAX_NOTIONAL_EXCEEDED");

        TradeProposal tooMuchLoss = proposal(TradeProposalAction.L4_ADJUST_PREAPPROVED, policy,
                ACCOUNT, INSTRUMENT, BigDecimal.TEN, new BigDecimal("21"),
                NOW.minusSeconds(5), NOW.plusSeconds(30));
        assertReason(() -> policy.requireAllows(tooMuchLoss, risk, NOW), "L4_LOSS_AT_STOP_EXCEEDED");

        DailyLossSnapshot incomplete = DailyLossSnapshot.create(ACCOUNT, "USD",
                LocalDate.of(2026, 8, 24), ZoneOffset.UTC, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, NOW.minusSeconds(1), D6, RiskSnapshotCompleteness.UNKNOWN);
        assertReason(() -> policy.requireAllows(l4Proposal(policy), incomplete, NOW),
                "L4_DAILY_LOSS_INCOMPLETE");

        DailyLossSnapshot stale = DailyLossSnapshot.create(ACCOUNT, "USD",
                LocalDate.of(2026, 8, 24), ZoneOffset.UTC, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, NOW.minusSeconds(31), D6, RiskSnapshotCompleteness.COMPLETE);
        assertReason(() -> policy.requireAllows(l4Proposal(policy), stale, NOW), "L4_DAILY_LOSS_STALE");

        DailyLossSnapshot cumulative = DailyLossSnapshot.create(ACCOUNT, "USD",
                LocalDate.of(2026, 8, 24), ZoneOffset.UTC, new BigDecimal("85"),
                new BigDecimal("10"), new BigDecimal("900"), NOW.minusSeconds(1), D6,
                RiskSnapshotCompleteness.COMPLETE);
        assertReason(() -> policy.requireAllows(l4Proposal(policy), cumulative, NOW),
                "L4_CUMULATIVE_NOTIONAL_EXCEEDED");
    }

    @Test
    void exactOperatorAgentProposalPolicyContextAndConsumerIdentityAreRechecked() {
        BoundedAutonomyPolicy policy = policy();
        TradeProposal proposal = l4Proposal(policy);
        DailyLossSnapshot risk = lossSnapshot();
        L4BoundedAutonomyService service = service(new InMemoryCapabilityTokenStore());
        L4CapabilityToken token = service.authorize(proposal, "operator", policy, risk,
                reference(AuthorizationKind.PREAPPROVED_L4_POLICY_GRANT, proposal, "operator",
                        risk.snapshotSha256(), "grant-bind"));

        CapabilityUseRequest wrongOperator = CapabilityUseRequest.create(token, proposal, policy,
                "other-operator", proposal.agentIdentity(), D1, D2, D3, D4, D5,
                NOW, NOW.plusSeconds(20));
        assertReason(() -> service.consumeCapability(token, wrongOperator,
                proposal, policy, risk), "CAPABILITY_USE_CONSUMER_MISMATCH");

        DailyLossSnapshot changedRisk = DailyLossSnapshot.create(ACCOUNT, "USD",
                LocalDate.of(2026, 8, 24), ZoneOffset.UTC, new BigDecimal("21"),
                new BigDecimal("5"), new BigDecimal("100"), NOW.minusSeconds(5), D6,
                RiskSnapshotCompleteness.COMPLETE);
        assertReason(() -> service.consumeCapability(token, capabilityUse(token, proposal, policy),
                proposal, policy, changedRisk), "CAPABILITY_CONTEXT_MISMATCH");
    }

    @Test
    void revocationPreventsAuthorizationOrCapabilityConsumption() {
        BoundedAutonomyPolicy policy = policy();
        TradeProposal proposal = l4Proposal(policy);
        DailyLossSnapshot risk = lossSnapshot();
        InMemoryCapabilityTokenStore capabilityStore = new InMemoryCapabilityTokenStore();
        L4BoundedAutonomyService service = service(capabilityStore);
        L4CapabilityToken token = service.authorize(proposal, "operator", policy, risk,
                reference(AuthorizationKind.PREAPPROVED_L4_POLICY_GRANT, proposal, "operator",
                        risk.snapshotSha256(), "grant-revoke"));

        service.revokeCapability(token.tokenId(), "operator incident");
        assertReason(() -> service.consumeCapability(token, capabilityUse(token, proposal, policy),
                proposal, policy, risk), "CAPABILITY_REVOKED");

        InMemoryCapabilityTokenStore secondCapabilities = new InMemoryCapabilityTokenStore();
        L4BoundedAutonomyService second = service(secondCapabilities);
        second.revokeProposal(proposal.proposalId(), "security", "global halt");
        assertReason(() -> second.authorize(proposal, "operator", policy, risk,
                reference(AuthorizationKind.PREAPPROVED_L4_POLICY_GRANT, proposal, "operator",
                        risk.snapshotSha256(), "grant-after-revoke")), "PROPOSAL_REVOKED");
    }

    @Test
    void atomicStoreRejectsTwoAuthorizationsAgainstSameRiskSnapshotHead() throws Exception {
        BoundedAutonomyPolicy policy = policy();
        TradeProposal proposal = l4Proposal(policy);
        DailyLossSnapshot risk = lossSnapshot();
        InMemoryCapabilityTokenStore capabilities = new InMemoryCapabilityTokenStore();
        AtomicInteger ids = new AtomicInteger();
        L4BoundedAutonomyService service = new L4BoundedAutonomyService(new ExactVerifier(),
                new InMemoryRevocationStore(), new InMemoryAuthorizationTokenStore(), capabilities,
                CLOCK, () -> "auth-use-" + ids.incrementAndGet(),
                () -> "cap-" + ids.incrementAndGet());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> authorizeAfter(start, service, policy,
                    proposal, risk, "grant-concurrent-1"));
            Future<Boolean> second = executor.submit(() -> authorizeAfter(start, service, policy,
                    proposal, risk, "grant-concurrent-2"));
            start.countDown();
            assertThat((first.get() ? 1 : 0) + (second.get() ? 1 : 0)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void authorizationCannotBeSelfSignedAndCapabilityNeedsTrustedConsumerVerifier() {
        BoundedAutonomyPolicy policy = policy();
        TradeProposal proposal = l4Proposal(policy);
        DailyLossSnapshot risk = lossSnapshot();
        AuthorizationReference selfSigned = new AuthorizationReference(
                "grant-self", D5, AuthorizationKind.PREAPPROVED_L4_POLICY_GRANT,
                TrustedAuthorizationVerifier.L4_AUDIENCE, "operator", proposal.agentIdentity(),
                proposal.governanceContext().deploymentEnvironment(),
                proposal.governanceContext().governanceSubjectSha256(), proposal.proposalId(),
                proposal.proposalSha256(), proposal.policyId(), proposal.policyVersion(),
                proposal.policyArtifactSha256(), risk.snapshotSha256(), proposal.agentIdentity(),
                proposal.expiresAt());
        assertReason(() -> service(new InMemoryCapabilityTokenStore()).authorize(
                proposal, "operator", policy, risk, selfSigned), "AUTHORIZATION_NOT_INDEPENDENT");

        ExactVerifier delegate = new ExactVerifier();
        TrustedAuthorizationVerifier authOnly = delegate::verifyAndConsume;
        L4BoundedAutonomyService authOnlyService = new L4BoundedAutonomyService(authOnly,
                new InMemoryRevocationStore(), new InMemoryAuthorizationTokenStore(),
                new InMemoryCapabilityTokenStore(), CLOCK, () -> "auth-only-use", () -> "auth-only-cap");
        L4CapabilityToken token = authOnlyService.authorize(proposal, "operator", policy, risk,
                reference(AuthorizationKind.PREAPPROVED_L4_POLICY_GRANT, proposal, "operator",
                        risk.snapshotSha256(), "grant-auth-only"));
        assertReason(() -> authOnlyService.consumeCapability(
                token, capabilityUse(token, proposal, policy), proposal, policy, risk),
                "CAPABILITY_CONSUMER_VERIFIER_NOT_WIRED");
    }

    @Test
    void consumerVerifierMustBindCommandValuationRiskReservationAndDecisionEnvelope() {
        assertConsumerVerifierMismatch(value -> copyConsumer(value,
                        value.executionCandidateSha256(), D6, value.valuationSnapshotSha256(),
                        value.riskBudgetSha256(), value.riskReservationSha256(),
                        value.orderDecisionEnvelopeSha256()),
                "CAPABILITY_CONSUMER_EXECUTION_CANDIDATE_MISMATCH");
        assertConsumerVerifierMismatch(value -> copyConsumer(value,
                        value.executionCandidateSha256(), value.commandSha256(), D6,
                        value.riskBudgetSha256(), value.riskReservationSha256(),
                        value.orderDecisionEnvelopeSha256()),
                "CAPABILITY_CONSUMER_VALUATION_MISMATCH");
        assertConsumerVerifierMismatch(value -> copyConsumer(value,
                        value.executionCandidateSha256(), value.commandSha256(),
                        value.valuationSnapshotSha256(), D6, value.riskReservationSha256(),
                        value.orderDecisionEnvelopeSha256()),
                "CAPABILITY_CONSUMER_RISK_MISMATCH");
        assertConsumerVerifierMismatch(value -> copyConsumer(value,
                        value.executionCandidateSha256(), value.commandSha256(),
                        value.valuationSnapshotSha256(), value.riskBudgetSha256(), D6,
                        value.orderDecisionEnvelopeSha256()),
                "CAPABILITY_CONSUMER_RISK_MISMATCH");
        assertConsumerVerifierMismatch(value -> copyConsumer(value,
                        value.executionCandidateSha256(), value.commandSha256(),
                        value.valuationSnapshotSha256(), value.riskBudgetSha256(),
                        value.riskReservationSha256(), D6),
                "CAPABILITY_CONSUMER_ENVELOPE_MISMATCH");
    }

    private static L4BoundedAutonomyService service(InMemoryCapabilityTokenStore capabilities) {
        return new L4BoundedAutonomyService(new ExactVerifier(), new InMemoryRevocationStore(),
                new InMemoryAuthorizationTokenStore(), capabilities, CLOCK,
                () -> "authorization-use", () -> "capability-id");
    }

    private static void assertConsumerVerifierMismatch(
            UnaryOperator<VerifiedCapabilityConsumer> mutation,
            String reasonCode) {
        BoundedAutonomyPolicy policy = policy();
        TradeProposal proposal = l4Proposal(policy);
        DailyLossSnapshot risk = lossSnapshot();
        L4BoundedAutonomyService service = new L4BoundedAutonomyService(
                new ExactVerifier(UnaryOperator.identity(), mutation),
                new InMemoryRevocationStore(), new InMemoryAuthorizationTokenStore(),
                new InMemoryCapabilityTokenStore(), CLOCK,
                () -> "verification-use", () -> "verification-capability");
        L4CapabilityToken token = service.authorize(proposal, "operator", policy, risk,
                reference(AuthorizationKind.PREAPPROVED_L4_POLICY_GRANT, proposal, "operator",
                        risk.snapshotSha256(), "verification-grant"));
        assertReason(() -> service.consumeCapability(
                token, capabilityUse(token, proposal, policy), proposal, policy, risk), reasonCode);
    }

    private static boolean authorizeAfter(
            CountDownLatch start,
            L4BoundedAutonomyService service,
            BoundedAutonomyPolicy policy,
            TradeProposal proposal,
            DailyLossSnapshot risk,
            String grantId) throws InterruptedException {
        start.await();
        try {
            service.authorize(proposal, "operator", policy, risk,
                    reference(AuthorizationKind.PREAPPROVED_L4_POLICY_GRANT, proposal, "operator",
                            risk.snapshotSha256(), grantId));
            return true;
        } catch (AgentAutonomyViolationException expected) {
            return false;
        }
    }

    private static void assertReason(Runnable action, String reasonCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(AgentAutonomyViolationException.class)
                .extracting("reasonCode").isEqualTo(reasonCode);
    }
}
