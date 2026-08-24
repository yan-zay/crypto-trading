package com.tj.crypto.strategy.lifecycle;

import com.tj.crypto.governance.evidence.GovernanceSubject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Deterministic, fail-closed strategy promotion coordinator.
 *
 * <p>This service manages release evidence only. Runtime execution must additionally present the
 * exact artifact digest to {@link StrategyPromotionGate}; a strategy name alone is never a release
 * identity.</p>
 */
@Service
public class StrategyLifecycleService {
    private final StrategyLifecycleStore store;
    private final StrategyArtifactSignatureVerifier signatureVerifier;
    private final ExternalPromotionReadinessProvider externalReadinessProvider;
    private final RegisteredResearchEvidenceProvider registeredResearchEvidenceProvider;
    private final StrategyLifecyclePolicy policy;
    private final Clock clock;

    @Autowired
    public StrategyLifecycleService(StrategyLifecycleStore store,
                                    StrategyArtifactSignatureVerifier signatureVerifier,
                                    ExternalPromotionReadinessProvider externalReadinessProvider,
                                    RegisteredResearchEvidenceProvider registeredResearchEvidenceProvider) {
        this(store, signatureVerifier, externalReadinessProvider,
                registeredResearchEvidenceProvider, Clock.systemUTC());
    }

    StrategyLifecycleService(StrategyLifecycleStore store,
                             StrategyArtifactSignatureVerifier signatureVerifier,
                             ExternalPromotionReadinessProvider externalReadinessProvider,
                             RegisteredResearchEvidenceProvider registeredResearchEvidenceProvider,
                             Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.signatureVerifier = Objects.requireNonNull(signatureVerifier, "signatureVerifier");
        this.externalReadinessProvider = Objects.requireNonNull(externalReadinessProvider,
                "externalReadinessProvider");
        this.registeredResearchEvidenceProvider = Objects.requireNonNull(
                registeredResearchEvidenceProvider, "registeredResearchEvidenceProvider");
        this.policy = new StrategyLifecyclePolicy();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public LifecycleDecision registerDraft(SignedStrategyArtifact artifact, LifecycleActor actor) {
        Instant now = Instant.now(clock);
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(actor, "actor");
        Optional<StrategyLifecycleState> existing = store.lockState(artifact.strategyName());
        if (!actor.can(LifecyclePermission.SUBMIT_ARTIFACT)
                || !actor.operator().equals(artifact.submittedBy())) {
            return denied(existing.orElse(null), artifact.strategyName(), artifact.artifactSha256(),
                    StrategyLifecycleAction.REGISTER_DRAFT, LifecycleReason.UNAUTHORIZED,
                    "artifact submitter must possess SUBMIT_ARTIFACT and match the audited operator", actor, now);
        }
        ArtifactVerification verification = signatureVerifier.verify(artifact, now);
        if (!verification.valid()) {
            return denied(existing.orElse(null), artifact.strategyName(), artifact.artifactSha256(),
                    StrategyLifecycleAction.REGISTER_DRAFT, verification.reason(), verification.detail(), actor, now);
        }
        if (artifact.submittedAt().isAfter(now)) {
            return denied(existing.orElse(null), artifact.strategyName(), artifact.artifactSha256(),
                    StrategyLifecycleAction.REGISTER_DRAFT, LifecycleReason.ARTIFACT_SIGNATURE_INVALID,
                    "artifact submission timestamp is in the future", actor, now);
        }
        StrategyLifecycleState current = existing.orElse(null);
        if (current != null && current.stage() != StrategyLifecycleStage.DRAFT
                && current.stage() != StrategyLifecycleStage.ROLLED_BACK) {
            return denied(current, artifact.strategyName(), artifact.artifactSha256(),
                    StrategyLifecycleAction.REGISTER_DRAFT, LifecycleReason.INVALID_TRANSITION,
                    "a new artifact can only replace DRAFT or ROLLED_BACK", actor, now);
        }
        Optional<SignedStrategyArtifact> storedArtifact = store.findArtifact(artifact.artifactSha256());
        if (storedArtifact.isPresent() && !sameArtifact(storedArtifact.get(), artifact)) {
            return denied(current, artifact.strategyName(), artifact.artifactSha256(),
                    StrategyLifecycleAction.REGISTER_DRAFT, LifecycleReason.ARTIFACT_HASH_MISMATCH,
                    "content address already belongs to different immutable metadata", actor, now);
        }
        if (storedArtifact.isEmpty()) store.insertArtifact(artifact);

        StrategyLifecycleState next;
        if (current == null) {
            next = new StrategyLifecycleState(artifact.strategyName(), artifact.artifactSha256(), null,
                    StrategyLifecycleStage.DRAFT, 0, 0, actor.operator(), now, LifecycleReason.ACCEPTED);
            store.insertState(next);
        } else {
            next = current.move(StrategyLifecycleStage.DRAFT, artifact.artifactSha256(),
                    current.artifactSha256(), current.reviewCycle() + 1,
                    actor.operator(), now, LifecycleReason.ACCEPTED);
            update(current, next);
        }
        audit(next, current == null ? null : current.stage(), StrategyLifecycleStage.DRAFT,
                StrategyLifecycleAction.REGISTER_DRAFT, true, LifecycleReason.ACCEPTED,
                "signed content-addressed artifact registered; expiresAt=" + artifact.expiresAt(),
                actor.operator(), null, now);
        return allowed(next, "signed artifact registered", now);
    }

    @Transactional
    public LifecycleDecision transition(String strategyName, StrategyLifecycleAction action,
                                        LifecycleActor actor) {
        Instant now = Instant.now(clock);
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(actor, "actor");
        StrategyLifecycleState current = requireState(strategyName);
        current = enforceLocked(current, now);

        LifecyclePermission permission = permission(action);
        if (permission == null || !actor.can(permission)) {
            return denied(current, strategyName, current.artifactSha256(), action,
                    LifecycleReason.UNAUTHORIZED, "operator lacks the required lifecycle capability", actor, now);
        }
        StrategyLifecycleStage expected;
        StrategyLifecycleStage target;
        long reviewCycle = current.reviewCycle();
        PolicyAssessment assessment = null;
        switch (action) {
            case SUBMIT_REVIEW -> {
                if (current.stage() != StrategyLifecycleStage.DRAFT
                        && current.stage() != StrategyLifecycleStage.ROLLED_BACK) {
                    return invalidTransition(current, action, actor, now);
                }
                expected = current.stage();
                target = StrategyLifecycleStage.REVIEW;
                reviewCycle++;
            }
            case APPROVE -> {
                expected = StrategyLifecycleStage.REVIEW;
                target = StrategyLifecycleStage.APPROVED;
                if (current.stage() != expected) return invalidTransition(current, action, actor, now);
                LifecycleDecision approvalGate = approvalGate(current, actor, now, action);
                if (approvalGate != null) return approvalGate;
            }
            case START_SHADOW -> {
                expected = StrategyLifecycleStage.APPROVED;
                target = StrategyLifecycleStage.SHADOW;
                if (current.stage() != expected) return invalidTransition(current, action, actor, now);
                assessment = policy.assess(target, evidence(current), now);
                if (!assessment.allowed()) return policyDenied(current, action, actor, assessment, now);
                LifecycleDecision research = researchPolicyDenied(current, action, actor, now);
                if (research != null) return research;
            }
            case START_CANARY -> {
                expected = StrategyLifecycleStage.SHADOW;
                target = StrategyLifecycleStage.CANARY;
                if (current.stage() != expected) return invalidTransition(current, action, actor, now);
                assessment = policy.assess(target, evidence(current), now);
                if (!assessment.allowed()) return policyDenied(current, action, actor, assessment, now);
                LifecycleDecision external = externalPolicyDenied(current, action, actor, assessment, now);
                if (external != null) return external;
            }
            case PROMOTE -> {
                expected = StrategyLifecycleStage.CANARY;
                target = StrategyLifecycleStage.PROMOTED;
                if (current.stage() != expected) return invalidTransition(current, action, actor, now);
                assessment = policy.assess(target, evidence(current), now);
                if (!assessment.allowed()) return policyDenied(current, action, actor, assessment, now);
                LifecycleDecision external = externalPolicyDenied(current, action, actor, assessment, now);
                if (external != null) return external;
            }
            default -> {
                return denied(current, strategyName, current.artifactSha256(), action,
                        LifecycleReason.INVALID_TRANSITION, "action requires its dedicated operation", actor, now);
            }
        }
        if (current.stage() != expected) return invalidTransition(current, action, actor, now);
        StrategyLifecycleState next = current.move(target, current.artifactSha256(),
                current.previousArtifactSha256(), reviewCycle, actor.operator(), now, LifecycleReason.ACCEPTED);
        update(current, next);
        String evidenceDigest = assessment == null ? policy.evidenceSetDigest(evidence(next))
                : assessment.evidenceSetSha256();
        audit(next, current.stage(), target, action, true, LifecycleReason.ACCEPTED,
                "policy transition accepted", actor.operator(), evidenceDigest, now);
        return allowed(next, "transition accepted", now);
    }

    @Transactional
    public LifecycleDecision recordApproval(String strategyName, ApprovalDecision decision,
                                            Instant expiresAt, LifecycleActor actor) {
        Instant now = Instant.now(clock);
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(actor, "actor");
        StrategyLifecycleState current = enforceLocked(requireState(strategyName), now);
        if (!actor.can(LifecyclePermission.APPROVE_STRATEGY)) {
            return denied(current, strategyName, current.artifactSha256(),
                    StrategyLifecycleAction.RECORD_APPROVAL, LifecycleReason.UNAUTHORIZED,
                    "operator lacks APPROVE_STRATEGY", actor, now);
        }
        if (current.stage() != StrategyLifecycleStage.REVIEW) {
            return invalidTransition(current, StrategyLifecycleAction.RECORD_APPROVAL, actor, now);
        }
        SignedStrategyArtifact artifact = artifact(current);
        if (artifact.submittedBy().equals(actor.operator())) {
            return denied(current, strategyName, current.artifactSha256(),
                    StrategyLifecycleAction.RECORD_APPROVAL, LifecycleReason.SELF_APPROVAL_FORBIDDEN,
                    "artifact submitter cannot approve the same release", actor, now);
        }
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            return denied(current, strategyName, current.artifactSha256(),
                    StrategyLifecycleAction.RECORD_APPROVAL, LifecycleReason.APPROVAL_EXPIRED,
                    "approval expiry must be in the future", actor, now);
        }
        List<StrategyApproval> existing = store.approvals(strategyName,
                current.artifactSha256(), current.reviewCycle());
        String evidenceDigest = policy.evidenceSetDigest(evidence(current));
        if (existing.stream().anyMatch(item -> item.approvedBy().equals(actor.operator())
                && item.expiresAt().isAfter(now)
                && item.evidenceSetSha256().equals(evidenceDigest))) {
            return denied(current, strategyName, current.artifactSha256(),
                    StrategyLifecycleAction.RECORD_APPROVAL, LifecycleReason.DUPLICATE_APPROVER,
                    "one operator contributes at most one current decision per review cycle", actor, now);
        }
        String approvalScope = Hashes.sha256(String.join("|", strategyName,
                current.artifactSha256(), Long.toString(current.reviewCycle()), evidenceDigest));
        StrategyApproval approval = new StrategyApproval(UUID.randomUUID().toString(), strategyName,
                current.artifactSha256(), current.reviewCycle(), decision, approvalScope,
                evidenceDigest, expiresAt, actor.operator(), now);
        store.insertApproval(approval);
        StrategyLifecycleState next = current;
        LifecycleReason reason = LifecycleReason.ACCEPTED;
        if (decision == ApprovalDecision.REJECT) {
            reason = LifecycleReason.APPROVAL_REJECTED;
            next = current.move(StrategyLifecycleStage.DRAFT, current.artifactSha256(),
                    current.previousArtifactSha256(), current.reviewCycle(), actor.operator(), now, reason);
            update(current, next);
        }
        audit(next, current.stage(), next.stage(), StrategyLifecycleAction.RECORD_APPROVAL, true, reason,
                "decision=" + decision + "; approvalId=" + approval.approvalId()
                        + "; expiresAt=" + expiresAt + "; scope=" + approvalScope,
                actor.operator(), evidenceDigest, now);
        return allowed(next, "approval decision recorded", now);
    }

    @Transactional
    public LifecycleDecision recordEvidence(String strategyName, EvidenceSubmission submission,
                                            LifecycleActor actor) {
        Instant now = Instant.now(clock);
        Objects.requireNonNull(submission, "submission");
        Objects.requireNonNull(actor, "actor");
        StrategyLifecycleState current = enforceLocked(requireState(strategyName), now);
        if (!actor.can(LifecyclePermission.RECORD_EVIDENCE)) {
            return denied(current, strategyName, current.artifactSha256(),
                    StrategyLifecycleAction.RECORD_EVIDENCE, LifecycleReason.UNAUTHORIZED,
                    "operator lacks RECORD_EVIDENCE", actor, now);
        }
        if (!submission.contentAddressMatches()) {
            return denied(current, strategyName, current.artifactSha256(),
                    StrategyLifecycleAction.RECORD_EVIDENCE, LifecycleReason.EVIDENCE_HASH_MISMATCH,
                    "evidence bytes do not match the declared SHA-256", actor, now);
        }
        if (submission.capturedAt().isAfter(now) || !submission.expiresAt().isAfter(now)) {
            return denied(current, strategyName, current.artifactSha256(),
                    StrategyLifecycleAction.RECORD_EVIDENCE, LifecycleReason.EVIDENCE_EXPIRED,
                    "evidence is expired or not yet valid", actor, now);
        }
        if (submission.outcome() == EvidenceOutcome.PASS && registryBacked(submission.type())) {
            RegisteredResearchEvidence expected = registeredResearchEvidence(current, submission.type(), now);
            if (!expected.ready()) {
                return denied(current, strategyName, current.artifactSha256(),
                        StrategyLifecycleAction.RECORD_EVIDENCE,
                        LifecycleReason.RESEARCH_REGISTRY_BLOCKED,
                        "experiment registry evidence is not promotion-ready: "
                                + String.join(", ", expected.blockers()), actor, now);
            }
            if (!expected.matches(submission)) {
                return denied(current, strategyName, current.artifactSha256(),
                        StrategyLifecycleAction.RECORD_EVIDENCE,
                        LifecycleReason.EVIDENCE_HASH_MISMATCH,
                        "RESEARCH/REPRODUCIBILITY PASS must equal the canonical experiment-registry projection",
                        actor, now);
            }
        }
        StrategyEvidenceSnapshot snapshot = new StrategyEvidenceSnapshot(
                UUID.randomUUID().toString(), strategyName, current.artifactSha256(),
                submission.type(), submission.outcome(), submission.evidenceSha256(),
                submission.evidenceUri(), submission.capturedAt(), submission.expiresAt(),
                actor.operator(), now);
        store.insertEvidence(snapshot);
        String evidenceDigest = policy.evidenceSetDigest(evidence(current));
        audit(current, current.stage(), current.stage(), StrategyLifecycleAction.RECORD_EVIDENCE,
                true, LifecycleReason.ACCEPTED,
                "type=" + submission.type() + "; outcome=" + submission.outcome()
                        + "; snapshotId=" + snapshot.snapshotId() + "; evidenceSha256="
                        + submission.evidenceSha256() + "; expiresAt=" + submission.expiresAt(),
                actor.operator(), evidenceDigest, now);
        return allowed(current, "evidence snapshot recorded", now);
    }

    /**
     * Binds the managed runtime to one exact signed release and complete governance subject.
     * Configuration enablement alone never creates this assignment.
     */
    @Transactional
    public LifecycleDecision assignRelease(String strategyName, GovernanceSubject subject,
                                           StrategyLifecycleStage minimumStage,
                                           Instant expiresAt, LifecycleActor actor) {
        Instant now = Instant.now(clock);
        Objects.requireNonNull(actor, "actor");
        StrategyLifecycleState current = enforceLocked(requireState(strategyName), now);
        if (!actor.can(LifecyclePermission.ASSIGN_RELEASE)) {
            return denied(current, strategyName, current.artifactSha256(),
                    StrategyLifecycleAction.ASSIGN_RELEASE, LifecycleReason.UNAUTHORIZED,
                    "operator lacks ASSIGN_RELEASE", actor, now);
        }
        if (subject == null) {
            return denied(current, strategyName, current.artifactSha256(),
                    StrategyLifecycleAction.ASSIGN_RELEASE,
                    LifecycleReason.GOVERNANCE_SUBJECT_MISSING,
                    "complete GovernanceSubject is required", actor, now);
        }
        if (!current.artifactSha256().equals(subject.strategyArtifactSha256())) {
            return denied(current, strategyName, current.artifactSha256(),
                    StrategyLifecycleAction.ASSIGN_RELEASE,
                    LifecycleReason.GOVERNANCE_SUBJECT_MISMATCH,
                    "governance subject is bound to a different strategy artifact", actor, now);
        }
        if (minimumStage != StrategyLifecycleStage.SHADOW
                && minimumStage != StrategyLifecycleStage.CANARY
                && minimumStage != StrategyLifecycleStage.PROMOTED) {
            return denied(current, strategyName, current.artifactSha256(),
                    StrategyLifecycleAction.ASSIGN_RELEASE, LifecycleReason.INVALID_TRANSITION,
                    "runtime release stage must be SHADOW, CANARY or PROMOTED", actor, now);
        }
        if (current.stage() == StrategyLifecycleStage.ROLLED_BACK
                || current.stage().ordinal() < minimumStage.ordinal()) {
            return invalidTransition(current, StrategyLifecycleAction.ASSIGN_RELEASE, actor, now);
        }
        SignedStrategyArtifact signedArtifact = artifact(current);
        if (expiresAt == null || !expiresAt.isAfter(now)
                || expiresAt.isAfter(signedArtifact.expiresAt())) {
            return denied(current, strategyName, current.artifactSha256(),
                    StrategyLifecycleAction.ASSIGN_RELEASE,
                    LifecycleReason.RELEASE_ASSIGNMENT_EXPIRED,
                    "assignment expiry must be current and no later than signed artifact expiry",
                    actor, now);
        }
        Optional<StrategyReleaseAssignment> previous = store.lockReleaseAssignment(strategyName);
        long expectedVersion = previous.map(StrategyReleaseAssignment::assignmentVersion).orElse(-1L);
        StrategyReleaseAssignment next = new StrategyReleaseAssignment(
                UUID.randomUUID().toString(), strategyName, current.artifactSha256(), minimumStage,
                subject, expiresAt, expectedVersion + 1, actor.operator(), now);
        store.replaceReleaseAssignment(next, expectedVersion);
        audit(current, current.stage(), current.stage(), StrategyLifecycleAction.ASSIGN_RELEASE,
                true, LifecycleReason.ACCEPTED,
                "assignmentId=" + next.assignmentId() + "; version=" + next.assignmentVersion()
                        + "; minimumStage=" + minimumStage + "; subjectSha256=" + subject.sha256()
                        + "; expiresAt=" + expiresAt,
                actor.operator(), subject.sha256(), now);
        return allowed(current, "exact runtime release assignment recorded", now);
    }

    @Transactional
    public LifecycleDecision rollback(String strategyName, String targetArtifactSha256,
                                      String reason, LifecycleActor actor) {
        Instant now = Instant.now(clock);
        Objects.requireNonNull(actor, "actor");
        StrategyLifecycleState current = enforceLocked(requireState(strategyName), now);
        if (!actor.can(LifecyclePermission.ROLLBACK_STRATEGY)) {
            return denied(current, strategyName, current.artifactSha256(), StrategyLifecycleAction.ROLLBACK,
                    LifecycleReason.UNAUTHORIZED, "operator lacks ROLLBACK_STRATEGY", actor, now);
        }
        if (current.stage() == StrategyLifecycleStage.DRAFT
                || current.stage() == StrategyLifecycleStage.ROLLED_BACK) {
            return invalidTransition(current, StrategyLifecycleAction.ROLLBACK, actor, now);
        }
        String target = targetArtifactSha256 == null ? current.artifactSha256() : targetArtifactSha256;
        SignedStrategyArtifact rollbackArtifact = store.findArtifact(target).orElse(null);
        if (rollbackArtifact == null || !strategyName.equals(rollbackArtifact.strategyName())) {
            return denied(current, strategyName, target, StrategyLifecycleAction.ROLLBACK,
                    LifecycleReason.ROLLBACK_TARGET_INVALID,
                    "rollback target is not an immutable artifact of this strategy", actor, now);
        }
        StrategyLifecycleState next = current.move(StrategyLifecycleStage.ROLLED_BACK, target,
                current.artifactSha256(), current.reviewCycle() + 1,
                actor.operator(), now, LifecycleReason.ACCEPTED);
        update(current, next);
        audit(next, current.stage(), StrategyLifecycleStage.ROLLED_BACK,
                StrategyLifecycleAction.ROLLBACK, true, LifecycleReason.ACCEPTED,
                "rollback target=" + target + "; reason=" + safe(reason),
                actor.operator(), policy.evidenceSetDigest(evidence(current)), now);
        return allowed(next, "rollback completed", now);
    }

    /** Reads are policy-enforcing: stale evidence cannot leave a higher stage visible. */
    @Transactional
    public StrategyLifecycleState current(String strategyName) {
        return enforceLocked(requireState(strategyName), Instant.now(clock));
    }

    private StrategyLifecycleState enforceLocked(StrategyLifecycleState current, Instant now) {
        if (current.stage() == StrategyLifecycleStage.DRAFT
                || current.stage() == StrategyLifecycleStage.ROLLED_BACK) return current;
        SignedStrategyArtifact artifact = store.findArtifact(current.artifactSha256()).orElse(null);
        ArtifactVerification verification = artifact == null
                ? ArtifactVerification.denied(LifecycleReason.ARTIFACT_NOT_FOUND, "artifact is missing")
                : signatureVerifier.verify(artifact, now);
        StrategyLifecycleStage target = current.stage();
        LifecycleReason reason = verification.reason();
        String detail = verification.detail();
        if (!verification.valid()) {
            target = StrategyLifecycleStage.DRAFT;
        } else if (atLeast(current.stage(), StrategyLifecycleStage.APPROVED)
                && !validApprovals(current, now)) {
            target = StrategyLifecycleStage.REVIEW;
            reason = LifecycleReason.APPROVAL_EXPIRED;
            detail = "fewer than two current independent approvals remain";
        } else if (atLeast(current.stage(), StrategyLifecycleStage.CANARY)) {
            PolicyAssessment live = policy.assess(StrategyLifecycleStage.CANARY, evidence(current), now);
            if (!live.allowed()) {
                target = StrategyLifecycleStage.SHADOW;
                reason = live.reason();
                detail = live.detail();
            } else {
                ExternalPromotionReadiness external = externalReadiness(current, now);
                if (!external.currentAt(now)) {
                    target = StrategyLifecycleStage.SHADOW;
                    reason = LifecycleReason.EXTERNAL_READINESS_BLOCKED;
                    detail = "external readiness missing/expired: " + String.join(", ", external.blockers());
                }
            }
        }
        if (atLeast(target, StrategyLifecycleStage.SHADOW)) {
            PolicyAssessment shadow = policy.assess(StrategyLifecycleStage.SHADOW, evidence(current), now);
            if (!shadow.allowed()) {
                target = StrategyLifecycleStage.APPROVED;
                reason = shadow.reason();
                detail = shadow.detail();
            } else {
                RegisteredResearchEvidence research = researchReadiness(current, now);
                if (!research.ready()) {
                    target = StrategyLifecycleStage.APPROVED;
                    reason = LifecycleReason.RESEARCH_REGISTRY_BLOCKED;
                    detail = "experiment registry evidence missing/invalid: "
                            + String.join(", ", research.blockers());
                }
            }
        }
        if (target == current.stage()) return current;
        StrategyLifecycleState next = current.move(target, current.artifactSha256(),
                current.previousArtifactSha256(), current.reviewCycle(),
                LifecycleActor.policyGate().operator(), now, reason);
        update(current, next);
        audit(next, current.stage(), target, StrategyLifecycleAction.POLICY_DOWNGRADE,
                true, reason, detail, LifecycleActor.policyGate().operator(),
                policy.evidenceSetDigest(evidence(current)), now);
        return next;
    }

    private LifecycleDecision approvalGate(StrategyLifecycleState current, LifecycleActor actor,
                                           Instant now, StrategyLifecycleAction action) {
        List<StrategyApproval> all = store.approvals(current.strategyName(),
                current.artifactSha256(), current.reviewCycle());
        Map<String, StrategyApproval> latest = new LinkedHashMap<>();
        all.stream().sorted(Comparator.comparing(StrategyApproval::approvedAt))
                .forEach(item -> latest.put(item.approvedBy(), item));
        if (latest.values().stream().anyMatch(item -> item.decision() == ApprovalDecision.REJECT)) {
            return denied(current, current.strategyName(), current.artifactSha256(), action,
                    LifecycleReason.APPROVAL_REJECTED, "review cycle contains a rejection", actor, now);
        }
        String currentEvidenceDigest = policy.evidenceSetDigest(evidence(current));
        long count = latest.values().stream().filter(item -> item.validAt(now)
                && item.evidenceSetSha256().equals(currentEvidenceDigest)).count();
        if (count < StrategyLifecyclePolicy.REQUIRED_APPROVERS) {
            LifecycleReason reason = latest.isEmpty()
                    ? LifecycleReason.INSUFFICIENT_APPROVALS : LifecycleReason.APPROVAL_EXPIRED;
            return denied(current, current.strategyName(), current.artifactSha256(), action, reason,
                    "two current, distinct, non-submitter approvals are required", actor, now);
        }
        return null;
    }

    private boolean validApprovals(StrategyLifecycleState current, Instant now) {
        List<StrategyApproval> approvals = store.approvals(current.strategyName(),
                current.artifactSha256(), current.reviewCycle());
        String submitter = artifact(current).submittedBy();
        String evidenceDigest = policy.evidenceSetDigest(evidence(current));
        return approvals.stream().filter(item -> item.validAt(now)
                        && !item.approvedBy().equals(submitter)
                        && item.evidenceSetSha256().equals(evidenceDigest))
                .map(StrategyApproval::approvedBy).distinct().count()
                >= StrategyLifecyclePolicy.REQUIRED_APPROVERS;
    }

    private List<StrategyEvidenceSnapshot> evidence(StrategyLifecycleState state) {
        return store.evidence(state.strategyName(), state.artifactSha256());
    }

    private SignedStrategyArtifact artifact(StrategyLifecycleState state) {
        return store.findArtifact(state.artifactSha256())
                .orElseThrow(() -> new IllegalStateException("strategy artifact disappeared while state was locked"));
    }

    private StrategyLifecycleState requireState(String strategyName) {
        if (strategyName == null || strategyName.isBlank()) throw new IllegalArgumentException("strategyName is required");
        return store.lockState(strategyName)
                .orElseThrow(() -> new IllegalArgumentException("unknown strategy lifecycle: " + strategyName));
    }

    private LifecycleDecision invalidTransition(StrategyLifecycleState current,
                                                StrategyLifecycleAction action,
                                                LifecycleActor actor, Instant now) {
        return denied(current, current.strategyName(), current.artifactSha256(), action,
                LifecycleReason.INVALID_TRANSITION,
                "action " + action + " is not allowed from " + current.stage(), actor, now);
    }

    private LifecycleDecision policyDenied(StrategyLifecycleState current,
                                           StrategyLifecycleAction action,
                                           LifecycleActor actor,
                                           PolicyAssessment assessment,
                                           Instant now) {
        return denied(current, current.strategyName(), current.artifactSha256(), action,
                assessment.reason(), assessment.detail(), actor, now, assessment.evidenceSetSha256());
    }

    private LifecycleDecision externalPolicyDenied(StrategyLifecycleState current,
                                                    StrategyLifecycleAction action,
                                                    LifecycleActor actor,
                                                    PolicyAssessment internal,
                                                    Instant now) {
        ExternalPromotionReadiness external = externalReadiness(current, now);
        if (external.currentAt(now)) return null;
        String detail = "external legal/security/operations/live-truth readiness is missing or expired";
        if (!external.blockers().isEmpty()) detail += ": " + String.join(", ", external.blockers());
        String combinedDigest = external.evidenceSetSha256() == null
                ? internal.evidenceSetSha256()
                : Hashes.sha256(internal.evidenceSetSha256() + "|" + external.evidenceSetSha256());
        return denied(current, current.strategyName(), current.artifactSha256(), action,
                LifecycleReason.EXTERNAL_READINESS_BLOCKED, detail, actor, now, combinedDigest);
    }

    private ExternalPromotionReadiness externalReadiness(StrategyLifecycleState current, Instant now) {
        try {
            StrategyReleaseAssignment assignment = store.findReleaseAssignment(current.strategyName())
                    .orElse(null);
            if (assignment == null) {
                return new ExternalPromotionReadiness(false, null, now, now,
                        List.of("exact runtime release assignment is missing"));
            }
            if (!assignment.artifactSha256().equals(current.artifactSha256())) {
                return new ExternalPromotionReadiness(false, null, now, now,
                        List.of("release assignment belongs to a different artifact"));
            }
            if (!assignment.currentAt(now)) {
                return new ExternalPromotionReadiness(false, null, now, now,
                        List.of("release assignment is expired or not yet valid"));
            }
            GovernanceSubject subject = assignment.governanceSubject();
            ExternalPromotionReadiness result = externalReadinessProvider.assess(subject, now);
            if (result != null) return result;
        } catch (RuntimeException ignored) {
            // Policy decisions are fail-closed; error details may contain secrets and are not audited.
        }
        return new ExternalPromotionReadiness(false, null, now, now,
                List.of("external readiness lookup failed"));
    }

    private LifecycleDecision researchPolicyDenied(StrategyLifecycleState current,
                                                   StrategyLifecycleAction action,
                                                   LifecycleActor actor, Instant now) {
        RegisteredResearchEvidence readiness = researchReadiness(current, now);
        if (readiness.ready()) return null;
        return denied(current, current.strategyName(), current.artifactSha256(), action,
                LifecycleReason.RESEARCH_REGISTRY_BLOCKED,
                "experiment registry evidence is not promotion-ready: "
                        + String.join(", ", readiness.blockers()), actor, now);
    }

    private RegisteredResearchEvidence researchReadiness(StrategyLifecycleState current, Instant now) {
        for (StrategyEvidenceType type : List.of(StrategyEvidenceType.RESEARCH,
                StrategyEvidenceType.REPRODUCIBILITY)) {
            RegisteredResearchEvidence expected = registeredResearchEvidence(current, type, now);
            if (!expected.ready()) return expected;
            StrategyEvidenceSnapshot latest = evidence(current).stream()
                    .filter(snapshot -> snapshot.type() == type)
                    .max(Comparator.comparing(StrategyEvidenceSnapshot::recordedAt))
                    .orElse(null);
            if (latest == null || latest.outcome() != EvidenceOutcome.PASS
                    || !expected.evidenceSha256().equals(latest.evidenceSha256())
                    || !expected.evidenceUri().equals(latest.evidenceUri())) {
                return RegisteredResearchEvidence.denied(
                        "latest " + type + " snapshot is not bound to the current experiment registry projection");
            }
        }
        return registeredResearchEvidence(current, StrategyEvidenceType.RESEARCH, now);
    }

    private RegisteredResearchEvidence registeredResearchEvidence(StrategyLifecycleState current,
                                                                  StrategyEvidenceType type,
                                                                  Instant now) {
        try {
            RegisteredResearchEvidence result = registeredResearchEvidenceProvider.assess(
                    artifact(current), type, now);
            return result == null
                    ? RegisteredResearchEvidence.denied("experiment registry adapter returned no result")
                    : result;
        } catch (RuntimeException failure) {
            return RegisteredResearchEvidence.denied("experiment registry evidence lookup failed");
        }
    }

    private static boolean registryBacked(StrategyEvidenceType type) {
        return type == StrategyEvidenceType.RESEARCH
                || type == StrategyEvidenceType.REPRODUCIBILITY;
    }

    private LifecycleDecision denied(StrategyLifecycleState current, String strategyName,
                                     String artifactSha256, StrategyLifecycleAction action,
                                     LifecycleReason reason, String detail,
                                     LifecycleActor actor, Instant now) {
        return denied(current, strategyName, artifactSha256, action, reason, detail, actor, now, null);
    }

    private LifecycleDecision denied(StrategyLifecycleState current, String strategyName,
                                     String artifactSha256, StrategyLifecycleAction action,
                                     LifecycleReason reason, String detail,
                                     LifecycleActor actor, Instant now, String evidenceDigest) {
        audit(strategyName, artifactSha256, current == null ? 0 : current.reviewCycle(),
                current == null ? null : current.stage(), current == null ? null : current.stage(),
                action, false, reason, detail, actor.operator(), evidenceDigest, now);
        return new LifecycleDecision(false, strategyName, artifactSha256,
                current == null ? null : current.stage(), reason, detail, now);
    }

    private LifecycleDecision allowed(StrategyLifecycleState state, String detail, Instant now) {
        return new LifecycleDecision(true, state.strategyName(), state.artifactSha256(),
                state.stage(), LifecycleReason.ACCEPTED, detail, now);
    }

    private void update(StrategyLifecycleState current, StrategyLifecycleState next) {
        if (!store.updateState(next, current.stateVersion())) {
            throw new IllegalStateException(LifecycleReason.CONCURRENT_UPDATE.name());
        }
    }

    private void audit(StrategyLifecycleState state,
                       StrategyLifecycleStage from, StrategyLifecycleStage to,
                       StrategyLifecycleAction action, boolean accepted,
                       LifecycleReason reason, String detail, String operator,
                       String evidenceDigest, Instant now) {
        String strategyName = state == null ? "unknown" : state.strategyName();
        String artifact = state == null ? null : state.artifactSha256();
        audit(strategyName, artifact, state == null ? 0 : state.reviewCycle(),
                from, to, action, accepted, reason, detail, operator, evidenceDigest, now);
    }

    private void audit(String strategyName, String artifact, long reviewCycle,
                       StrategyLifecycleStage from, StrategyLifecycleStage to,
                       StrategyLifecycleAction action, boolean accepted,
                       LifecycleReason reason, String detail, String operator,
                       String evidenceDigest, Instant now) {
        StrategyLifecycleEvent prior = store.latestEvent(strategyName).orElse(null);
        long sequence = prior == null ? 1 : prior.sequenceNo() + 1;
        String previousHash = prior == null ? LifecycleAuditHasher.GENESIS : prior.eventSha256();
        StrategyLifecycleEvent unhashed = new StrategyLifecycleEvent(
                UUID.randomUUID().toString(), strategyName, artifact, sequence, reviewCycle,
                from, to, action, accepted, reason, safe(detail), operator, evidenceDigest,
                now, previousHash, LifecycleAuditHasher.GENESIS);
        StrategyLifecycleEvent event = new StrategyLifecycleEvent(
                unhashed.eventId(), unhashed.strategyName(), unhashed.artifactSha256(),
                unhashed.sequenceNo(), unhashed.reviewCycle(), unhashed.fromStage(), unhashed.toStage(),
                unhashed.action(), unhashed.accepted(), unhashed.reason(), unhashed.detail(),
                unhashed.operator(), unhashed.evidenceSetSha256(), unhashed.occurredAt(),
                unhashed.previousEventSha256(), LifecycleAuditHasher.hash(unhashed));
        store.appendEvent(event);
    }

    private static LifecyclePermission permission(StrategyLifecycleAction action) {
        return switch (action) {
            case SUBMIT_REVIEW -> LifecyclePermission.REQUEST_REVIEW;
            case APPROVE -> LifecyclePermission.APPROVE_STRATEGY;
            case START_SHADOW -> LifecyclePermission.START_SHADOW;
            case START_CANARY -> LifecyclePermission.START_CANARY;
            case PROMOTE -> LifecyclePermission.PROMOTE_STRATEGY;
            case ASSIGN_RELEASE -> LifecyclePermission.ASSIGN_RELEASE;
            default -> null;
        };
    }

    private static boolean atLeast(StrategyLifecycleStage current, StrategyLifecycleStage floor) {
        if (current == StrategyLifecycleStage.ROLLED_BACK) return false;
        return current.ordinal() >= floor.ordinal();
    }

    private static boolean sameArtifact(SignedStrategyArtifact left, SignedStrategyArtifact right) {
        return left.strategyName().equals(right.strategyName())
                && left.artifactVersion().equals(right.artifactVersion())
                && java.util.Arrays.equals(left.content(), right.content())
                && left.artifactSha256().equals(right.artifactSha256())
                && left.mediaType().equals(right.mediaType())
                && Objects.equals(left.artifactUri(), right.artifactUri())
                && left.signatureBase64().equals(right.signatureBase64())
                && left.signatureAlgorithm().equals(right.signatureAlgorithm())
                && left.signingKeyId().equals(right.signingKeyId())
                && left.signedAt().equals(right.signedAt())
                && left.expiresAt().equals(right.expiresAt())
                && left.submittedBy().equals(right.submittedBy())
                && left.submittedAt().equals(right.submittedAt())
                && Objects.equals(left.researchRegistrationId(), right.researchRegistrationId())
                && Objects.equals(left.researchRegistrationSha256(), right.researchRegistrationSha256());
    }

    private static String safe(String detail) {
        if (detail == null) return "";
        String normalized = detail.replace('\n', ' ').replace('\r', ' ');
        return normalized.substring(0, Math.min(2000, normalized.length()));
    }
}
