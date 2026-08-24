package com.tj.crypto.governance.approval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DualControlPolicyTest {
    private static final String HASH = "c".repeat(64);
    private static final String SUBJECT_HASH = "e".repeat(64);
    private static final long NOW = 10_000;

    @Test
    void grantsOnlyTwoDistinctMfaBoundApprovers() {
        DualControlRequest request = request(List.of(
                vote("risk-a", "RISK_MANAGER"),
                vote("admin-b", "ADMIN")));

        DualControlDecision decision = trustedPolicy().evaluate(request, NOW);

        assertThat(decision.granted()).isTrue();
        assertThat(decision.approvedBy()).containsExactly("risk-a", "admin-b");
        assertThat(decision.resourceSha256()).isEqualTo(HASH);
    }

    @Test
    void requesterDuplicateOrUnverifiedVotesFailClosed() {
        ApprovalVote requesterVote = vote("requester", "ADMIN");
        ApprovalVote duplicate = vote("risk-a", "RISK_MANAGER");
        DualControlRequest request = request(List.of(requesterVote, duplicate, duplicate));

        DualControlDecision decision = trustedPolicy().evaluate(request, NOW);

        assertThat(decision.granted()).isFalse();
        assertThat(decision.blockers()).anyMatch(value -> value.contains("own request"));
        assertThat(decision.blockers()).anyMatch(value -> value.contains("two distinct"));
    }

    @Test
    void expiryOrExplicitRejectionAlwaysBlocks() {
        ApprovalVote rejection = new ApprovalVote("admin-b", "ADMIN", false, 9_000,
                mfa("admin-b"), "risk unresolved");
        DualControlRequest request = request(List.of(vote("risk-a", "RISK_MANAGER"), rejection));

        DualControlDecision decision = trustedPolicy().evaluate(request, 21_000);

        assertThat(decision.granted()).isFalse();
        assertThat(decision.blockers()).anyMatch(value -> value.contains("not active"));
        assertThat(decision.blockers()).anyMatch(value -> value.contains("explicit rejection"));
    }

    @Test
    void futureVotesAndUnconfiguredIdentityProviderCannotApprove() {
        ApprovalVote future = new ApprovalVote("risk-a", "RISK_MANAGER", true, 12_000,
                mfa("risk-a"), "reviewed");
        DualControlRequest request = request(List.of(future, vote("admin-b", "ADMIN")));

        assertThat(trustedPolicy().evaluate(request, NOW).blockers())
                .anyMatch(value -> value.contains("validity window"));
        assertThat(new DualControlPolicy().evaluate(
                request(List.of(vote("risk-a", "RISK_MANAGER"), vote("admin-b", "ADMIN"))), NOW)
                .granted()).isFalse();
    }

    @Test
    void exactApprovalCanOnlyBeConsumedOnce() {
        HashSet<String> consumed = new HashSet<>();
        DualControlPolicy policy = trustedPolicy(consumption -> consumed.add(consumption.requestId()));
        DualControlRequest request = request(List.of(
                vote("risk-a", "RISK_MANAGER"), vote("admin-b", "ADMIN")));

        assertThat(policy.evaluate(request, NOW).granted()).isTrue();
        assertThat(policy.evaluate(request, NOW).granted()).isFalse();
    }

    @Test
    void verifierMustIndependentlyBindExactRequestActionResourceSubjectAndAudience() {
        MfaAttestationVerifier wrongAudience = (attestation, request, nowMs) ->
                trustedVerification(attestation, request, "wrong-audience",
                        "reservation-" + attestation.challengeId());
        DualControlDecision decision = new DualControlPolicy(wrongAudience, consumption -> true)
                .evaluate(request(List.of(vote("risk-a", "RISK_MANAGER"),
                        vote("admin-b", "ADMIN"))), NOW);

        assertThat(decision.granted()).isFalse();
        assertThat(decision.blockers()).anyMatch(value -> value.contains("NOT_VERIFIED"));
    }

    @Test
    void duplicateApproverCannotCreateTwoVotesOrTwoReservations() {
        AtomicReference<ApprovalConsumption> stored = new AtomicReference<>();
        DualControlPolicy policy = trustedPolicy(consumption -> {
            stored.set(consumption);
            return true;
        });
        DualControlRequest request = request(List.of(
                vote("risk-a", "RISK_MANAGER"),
                new ApprovalVote("risk-a", "ADMIN", true, 9_000,
                        new MfaAttestation("risk-a", "corp-idp", "challenge-risk-a-2",
                                "approval-1", ApprovalAction.STRATEGY_PROMOTE, HASH,
                                SUBJECT_HASH, 8_000, 15_000, HASH), "duplicate"),
                vote("admin-b", "ADMIN")));

        DualControlDecision decision = policy.evaluate(request, NOW);

        assertThat(decision.granted()).isFalse();
        assertThat(decision.blockers()).anyMatch(value -> value.contains("vote only once"));
        assertThat(stored.get()).isNull();
    }

    @Test
    void reservationConstructionAndStorageFailuresReturnFailClosedDecisions() {
        MfaAttestationVerifier invalidReservation = (attestation, request, nowMs) ->
                trustedVerification(attestation, request, DualControlPolicy.MFA_AUDIENCE,
                        "x".repeat(129));
        DualControlRequest request = request(List.of(
                vote("risk-a", "RISK_MANAGER"), vote("admin-b", "ADMIN")));

        assertThat(new DualControlPolicy(invalidReservation, consumption -> true)
                .evaluate(request, NOW).granted()).isFalse();
        DualControlDecision storageFailure = trustedPolicy(consumption -> {
            throw new IllegalStateException("database unavailable");
        }).evaluate(request, NOW);
        assertThat(storageFailure.granted()).isFalse();
        assertThat(storageFailure.blockers()).contains("approval consumption failed closed");
    }

    private DualControlRequest request(List<ApprovalVote> votes) {
        return new DualControlRequest("approval-1", ApprovalAction.STRATEGY_PROMOTE, HASH, SUBJECT_HASH,
                "requester", 1_000, 20_000, votes);
    }

    private ApprovalVote vote(String approver, String role) {
        return new ApprovalVote(approver, role, true, 9_000, mfa(approver), "reviewed");
    }

    private MfaAttestation mfa(String subject) {
        return new MfaAttestation(subject, "corp-idp", "challenge-" + subject,
                "approval-1", ApprovalAction.STRATEGY_PROMOTE, HASH,
                SUBJECT_HASH, 8_000, 15_000, HASH);
    }

    private DualControlPolicy trustedPolicy() {
        return trustedPolicy(consumption -> true);
    }

    private DualControlPolicy trustedPolicy(ApprovalConsumptionStore consumptionStore) {
        return new DualControlPolicy((attestation, request, nowMs) -> trustedVerification(
                        attestation, request, DualControlPolicy.MFA_AUDIENCE,
                        "reservation-" + attestation.challengeId()),
                consumptionStore);
    }

    private static MfaAssertionVerification trustedVerification(MfaAttestation attestation,
                                                                 DualControlRequest request,
                                                                 String audience,
                                                                 String reservationId) {
        return new MfaAssertionVerification(true, "test-idp-verifier", attestation.subject(),
                java.util.Set.of("ADMIN", "RISK_MANAGER", "OPERATOR"),
                attestation.provider(), attestation.challengeId(), request.requestId(),
                request.action(), request.resourceSha256(), request.governanceSubjectSha256(),
                audience, attestation.artifactSha256(), reservationId, "");
    }
}
