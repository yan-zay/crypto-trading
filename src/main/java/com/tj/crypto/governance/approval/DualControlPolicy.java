package com.tj.crypto.governance.approval;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Deterministic two-person approval policy. Actual identity and MFA verification stay external. */
@Service
public class DualControlPolicy {
    private static final Set<String> ELIGIBLE_ROLES = Set.of("ADMIN", "RISK_MANAGER", "OPERATOR");
    static final String MFA_AUDIENCE = "crypto-trading-governance-approval-v1";
    private static final long MAX_REQUEST_TTL_MS = Duration.ofMinutes(15).toMillis();

    private final MfaAttestationVerifier mfaVerifier;
    private final ApprovalConsumptionStore consumptionStore;

    @Autowired
    public DualControlPolicy(MfaAttestationVerifier mfaVerifier,
                             ApprovalConsumptionStore consumptionStore) {
        this.mfaVerifier = mfaVerifier;
        this.consumptionStore = consumptionStore;
    }

    /** Compatibility construction cannot approve without trusted MFA and a durable ledger. */
    DualControlPolicy() {
        this((attestation, request, nowMs) -> MfaAssertionVerification.untrusted(
                        "no trusted IdP/MFA verifier is configured"),
                consumption -> false);
    }

    public DualControlDecision evaluate(DualControlRequest request, long nowMs) {
        List<String> blockers = new ArrayList<>();
        Set<String> approvedBy = new LinkedHashSet<>();
        List<MfaReplayReservation> replayReservations = new ArrayList<>();
        Set<String> voteApprovers = new HashSet<>();
        Set<String> challengeIds = new HashSet<>();
        Set<String> reservationIds = new HashSet<>();
        boolean requestActive = true;
        if (nowMs < request.createdAtMs() || nowMs >= request.expiresAtMs()) {
            blockers.add("approval request is not active");
            requestActive = false;
        }
        if (request.expiresAtMs() - request.createdAtMs() > MAX_REQUEST_TTL_MS) {
            blockers.add("approval request TTL exceeds fifteen minutes");
            requestActive = false;
        }
        for (ApprovalVote vote : request.votes()) {
            if (!voteApprovers.add(vote.approver())) {
                blockers.add("approver may vote only once: " + vote.approver());
                continue;
            }
            if (!vote.approved()) {
                blockers.add("explicit rejection by " + vote.approver());
                continue;
            }
            if (request.requestedBy().equals(vote.approver())) {
                blockers.add("requester cannot approve their own request");
                continue;
            }
            if (!ELIGIBLE_ROLES.contains(vote.role())) {
                blockers.add("ineligible approval role for " + vote.approver());
                continue;
            }
            MfaAttestation attestation = vote.mfaAttestation();
            if (!challengeIds.add(attestation.challengeId())) {
                blockers.add("MFA challenge was reused within the request");
                continue;
            }
            if (!request.requestId().equals(attestation.requestId())
                    || request.action() != attestation.action()
                    || !request.resourceSha256().equals(attestation.resourceSha256())
                    || !request.governanceSubjectSha256()
                    .equals(attestation.governanceSubjectSha256())) {
                blockers.add("MFA assertion is bound to a different request or action");
                continue;
            }
            if (!vote.approver().equals(attestation.subject())) {
                blockers.add("MFA assertion subject differs from approver");
                continue;
            }
            if (vote.decidedAtMs() < request.createdAtMs()
                    || vote.decidedAtMs() >= request.expiresAtMs()
                    || vote.decidedAtMs() > nowMs
                    || attestation.verifiedAtMs() < request.createdAtMs()
                    || attestation.expiresAtMs() > request.expiresAtMs()
                    || !attestation.isActiveAt(vote.decidedAtMs())
                    || !attestation.isActiveAt(nowMs)) {
                blockers.add("MFA or vote is outside its validity window for " + vote.approver());
                continue;
            }
            if (!requestActive) continue;
            MfaAssertionVerification verification;
            try {
                verification = mfaVerifier.verify(attestation, request, nowMs);
            } catch (RuntimeException verificationFailure) {
                blockers.add("MFA verification failed for " + vote.approver());
                continue;
            }
            if (verification == null || !verification.trusted()
                    || !vote.approver().equals(verification.verifiedSubject())
                    || !verification.verifiedRoles().contains(vote.role())
                    || !attestation.provider().equals(verification.verifiedProvider())
                    || !attestation.challengeId().equals(verification.verifiedChallengeId())
                    || !request.requestId().equals(verification.verifiedRequestId())
                    || request.action() != verification.verifiedAction()
                    || !request.resourceSha256().equals(
                    verification.verifiedResourceSha256())
                    || !request.governanceSubjectSha256().equals(
                    verification.verifiedGovernanceSubjectSha256())
                    || !MFA_AUDIENCE.equals(verification.verifiedAudience())
                    || !attestation.artifactSha256()
                    .equals(verification.recomputedArtifactSha256())) {
                blockers.add("MFA identity or authority is NOT_VERIFIED for " + vote.approver());
                continue;
            }
            if (!reservationIds.add(verification.replayReservationId())) {
                blockers.add("MFA replay reservation was reused within the request");
                continue;
            }
            if (!approvedBy.add(vote.approver())) {
                blockers.add("approver may contribute only one approval: " + vote.approver());
                continue;
            }
            try {
                replayReservations.add(new MfaReplayReservation(
                        vote.approver(), verification.replayReservationId()));
            } catch (RuntimeException invalidReservation) {
                approvedBy.remove(vote.approver());
                blockers.add("MFA replay reservation is invalid for " + vote.approver());
            }
        }
        if (approvedBy.size() < 2) blockers.add("two distinct eligible approvers are required");
        long consumedAtMs = 0;
        if (blockers.isEmpty()) {
            try {
                ApprovalConsumption consumption = new ApprovalConsumption(request.requestId(),
                        request.action(), request.resourceSha256(),
                        request.governanceSubjectSha256(), request.requestedBy(),
                        List.copyOf(approvedBy), replayReservations,
                        request.createdAtMs(), request.expiresAtMs(), nowMs);
                if (consumptionStore.tryConsume(consumption)) {
                    consumedAtMs = nowMs;
                } else {
                    blockers.add("approval request was already consumed or durable consumption is unavailable");
                }
            } catch (RuntimeException consumptionFailure) {
                blockers.add("approval consumption failed closed");
            }
        }
        return new DualControlDecision(blockers.isEmpty(), request.requestId(), request.action(),
                request.resourceSha256(), request.governanceSubjectSha256(),
                List.copyOf(approvedBy), consumedAtMs, blockers);
    }
}
