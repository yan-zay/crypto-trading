package com.tj.crypto.strategy.lifecycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.tj.crypto.governance.evidence.GovernanceSubject;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyLifecycleServiceTest {
    private static final Instant BASE = Instant.parse("2026-08-20T00:00:00Z");
    private static final String STRATEGY = "golden-rsi";

    private InMemoryStore store;
    private MutableClock clock;
    private KeyPair keyPair;
    private StrategyLifecycleService service;
    private ExternalPromotionReadinessProvider externalReady;
    private RegisteredResearchEvidenceProvider registeredResearch;

    private final LifecycleActor author = actor("author",
            LifecyclePermission.SUBMIT_ARTIFACT, LifecyclePermission.REQUEST_REVIEW);
    private final LifecycleActor reviewerOne = actor("reviewer-1", LifecyclePermission.APPROVE_STRATEGY);
    private final LifecycleActor reviewerTwo = actor("reviewer-2", LifecyclePermission.APPROVE_STRATEGY);
    private final LifecycleActor releaseManager = actor("release-manager",
            LifecyclePermission.APPROVE_STRATEGY, LifecyclePermission.START_SHADOW,
            LifecyclePermission.START_CANARY, LifecyclePermission.PROMOTE_STRATEGY,
            LifecyclePermission.ROLLBACK_STRATEGY, LifecyclePermission.ASSIGN_RELEASE);
    private final LifecycleActor evidenceOfficer = actor("evidence-officer",
            LifecyclePermission.RECORD_EVIDENCE);

    @BeforeEach
    void setUp() throws Exception {
        store = new InMemoryStore();
        clock = new MutableClock(BASE);
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        TrustedSigningKey key = new TrustedSigningKey("release-key-1", "Ed25519",
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()), "ACTIVE",
                BASE.minus(Duration.ofDays(1)), BASE.plus(Duration.ofDays(365)), null);
        StrategyArtifactSignatureVerifier verifier = new Ed25519StrategyArtifactSignatureVerifier(
                keyId -> "release-key-1".equals(keyId) ? Optional.of(key) : Optional.empty());
        externalReady = (subject, now) -> new ExternalPromotionReadiness(
                true, sha256("external-readiness"), now.minusSeconds(1), now.plusSeconds(3600), List.of());
        registeredResearch = (artifact, type, now) -> {
            byte[] bytes = ("evidence:" + type).getBytes(StandardCharsets.UTF_8);
            return new RegisteredResearchEvidence(true, bytes, sha256(bytes),
                    "evidence://" + type.name().toLowerCase(), List.of());
        };
        service = new StrategyLifecycleService(store, verifier, externalReady, registeredResearch, clock);
    }

    @Test
    void followsTheOnlyAllowedPathWithSignedArtifactTwoApproversAndAuditableEvidence() throws Exception {
        SignedStrategyArtifact artifact = signedArtifact("v1", "author", BASE.plus(Duration.ofDays(30)));

        assertThat(service.registerDraft(artifact, author).stage()).isEqualTo(StrategyLifecycleStage.DRAFT);
        assertThat(service.transition(STRATEGY, StrategyLifecycleAction.SUBMIT_REVIEW, author).allowed()).isTrue();
        recordAllEvidence(Duration.ofDays(10));
        approveUntilApproved(BASE.plus(Duration.ofDays(2)));
        assertThat(service.transition(STRATEGY, StrategyLifecycleAction.START_SHADOW, releaseManager).allowed()).isTrue();
        assignCurrentRelease(artifact.artifactSha256());
        assertThat(service.transition(STRATEGY, StrategyLifecycleAction.START_CANARY, releaseManager).allowed()).isTrue();
        assertThat(service.transition(STRATEGY, StrategyLifecycleAction.PROMOTE, releaseManager).stage())
                .isEqualTo(StrategyLifecycleStage.PROMOTED);

        StrategyPromotionGate gate = new StrategyPromotionGate(service);
        assertThat(gate.allows(STRATEGY, artifact.artifactSha256(), StrategyLifecycleStage.PROMOTED)).isTrue();
        assertThat(gate.allows(STRATEGY, sha256("different release"), StrategyLifecycleStage.SHADOW)).isFalse();
        assertAuditChainIsValid();
        assertThat(store.events).allMatch(event -> event.operator() != null && !event.operator().isBlank());
        assertThat(store.approvals).allMatch(approval -> approval.expiresAt() != null
                && approval.evidenceSetSha256().matches("[0-9a-f]{64}"));
        assertThat(store.evidence).allMatch(snapshot -> snapshot.expiresAt() != null
                && snapshot.evidenceSha256().matches("[0-9a-f]{64}"));
    }

    @Test
    void rejectsTamperedAndIncorrectlySignedArtifactsBeforeDraftExists() throws Exception {
        SignedStrategyArtifact valid = signedArtifact("v1", "author", BASE.plus(Duration.ofDays(30)));
        SignedStrategyArtifact tampered = copy(valid, "tampered".getBytes(StandardCharsets.UTF_8),
                valid.artifactSha256(), valid.signatureBase64());
        LifecycleDecision hashDenied = service.registerDraft(tampered, author);
        assertThat(hashDenied.allowed()).isFalse();
        assertThat(hashDenied.reason()).isEqualTo(LifecycleReason.ARTIFACT_HASH_MISMATCH);

        SignedStrategyArtifact badSignature = copy(valid, valid.content(), valid.artifactSha256(),
                Base64.getEncoder().encodeToString(new byte[64]));
        LifecycleDecision signatureDenied = service.registerDraft(badSignature, author);
        assertThat(signatureDenied.allowed()).isFalse();
        assertThat(signatureDenied.reason()).isEqualTo(LifecycleReason.ARTIFACT_SIGNATURE_INVALID);
        assertThat(store.states).isEmpty();
        assertThat(store.events).extracting(StrategyLifecycleEvent::strategyName)
                .containsOnly(STRATEGY);
        assertAuditChainIsValid();
    }

    @Test
    void deniesSkippingStagesAndUnauthorizedOrSelfApproval() throws Exception {
        service.registerDraft(signedArtifact("v1", "author", BASE.plus(Duration.ofDays(30))), author);
        LifecycleDecision skipped = service.transition(STRATEGY, StrategyLifecycleAction.START_SHADOW,
                releaseManager);
        assertThat(skipped.reason()).isEqualTo(LifecycleReason.INVALID_TRANSITION);

        service.transition(STRATEGY, StrategyLifecycleAction.SUBMIT_REVIEW, author);
        LifecycleDecision unauthorized = service.recordApproval(STRATEGY, ApprovalDecision.APPROVE,
                BASE.plus(Duration.ofDays(1)), actor("observer"));
        assertThat(unauthorized.reason()).isEqualTo(LifecycleReason.UNAUTHORIZED);
        LifecycleDecision self = service.recordApproval(STRATEGY, ApprovalDecision.APPROVE,
                BASE.plus(Duration.ofDays(1)), actor("author", LifecyclePermission.APPROVE_STRATEGY));
        assertThat(self.reason()).isEqualTo(LifecycleReason.SELF_APPROVAL_FORBIDDEN);

        assertThat(service.recordApproval(STRATEGY, ApprovalDecision.APPROVE,
                BASE.plus(Duration.ofDays(1)), reviewerOne).allowed()).isTrue();
        LifecycleDecision duplicate = service.recordApproval(STRATEGY, ApprovalDecision.APPROVE,
                BASE.plus(Duration.ofDays(1)), reviewerOne);
        assertThat(duplicate.reason()).isEqualTo(LifecycleReason.DUPLICATE_APPROVER);
        LifecycleDecision oneOnly = service.transition(STRATEGY, StrategyLifecycleAction.APPROVE,
                releaseManager);
        assertThat(oneOnly.allowed()).isFalse();
        assertThat(oneOnly.reason()).isIn(LifecycleReason.INSUFFICIENT_APPROVALS,
                LifecycleReason.APPROVAL_EXPIRED);
        assertThat(service.current(STRATEGY).stage()).isEqualTo(StrategyLifecycleStage.REVIEW);
        assertAuditChainIsValid();
    }

    @Test
    void aReviewRejectionReturnsArtifactToDraftAndCannotBeHidden() throws Exception {
        service.registerDraft(signedArtifact("v1", "author", BASE.plus(Duration.ofDays(30))), author);
        service.transition(STRATEGY, StrategyLifecycleAction.SUBMIT_REVIEW, author);
        LifecycleDecision rejected = service.recordApproval(STRATEGY, ApprovalDecision.REJECT,
                BASE.plus(Duration.ofDays(1)), reviewerOne);

        assertThat(rejected.allowed()).isTrue();
        assertThat(service.current(STRATEGY).stage()).isEqualTo(StrategyLifecycleStage.DRAFT);
        assertThat(store.approvals).singleElement().satisfies(approval -> {
            assertThat(approval.decision()).isEqualTo(ApprovalDecision.REJECT);
            assertThat(approval.approvedBy()).isEqualTo("reviewer-1");
        });
        assertThat(store.events).anyMatch(event -> event.reason() == LifecycleReason.APPROVAL_REJECTED);
    }

    @Test
    void missingLegalSafetyOperationsOrLiveTruthBlocksCanary() throws Exception {
        toReview();
        for (StrategyEvidenceType type : StrategyEvidenceType.values()) {
            if (type != StrategyEvidenceType.LEGAL) recordEvidence(type, Duration.ofDays(5));
        }
        approveUntilApproved(BASE.plus(Duration.ofDays(2)));
        assertThat(service.transition(STRATEGY, StrategyLifecycleAction.START_SHADOW, releaseManager).allowed())
                .isTrue();

        LifecycleDecision denied = service.transition(STRATEGY, StrategyLifecycleAction.START_CANARY,
                releaseManager);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.reason()).isEqualTo(LifecycleReason.LEGAL_GATE_BLOCKED);
        assertThat(service.current(STRATEGY).stage()).isEqualTo(StrategyLifecycleStage.SHADOW);
    }

    @Test
    void independentExternalReadinessIsMandatoryEvenWhenInternalSnapshotsPass() throws Exception {
        service = new StrategyLifecycleService(store,
                verifierForCurrentKey(),
                (subject, now) -> new ExternalPromotionReadiness(false, null, now, now,
                        List.of("venue truth NOT_VERIFIED", "7d soak NOT_VERIFIED")),
                registeredResearch, clock);
        toReview();
        recordAllEvidence(Duration.ofDays(5));
        approveUntilApproved(BASE.plus(Duration.ofDays(2)));
        service.transition(STRATEGY, StrategyLifecycleAction.START_SHADOW, releaseManager);
        assignCurrentRelease(service.current(STRATEGY).artifactSha256());

        LifecycleDecision denied = service.transition(STRATEGY, StrategyLifecycleAction.START_CANARY,
                releaseManager);
        assertThat(denied.reason()).isEqualTo(LifecycleReason.EXTERNAL_READINESS_BLOCKED);
        assertThat(denied.detail()).contains("venue truth", "7d soak");
    }

    @Test
    void canaryRequiresExactReleaseAssignmentEvenWhenExternalProviderWouldApprove() throws Exception {
        toReview();
        recordAllEvidence(Duration.ofDays(5));
        approveUntilApproved(BASE.plus(Duration.ofDays(2)));
        service.transition(STRATEGY, StrategyLifecycleAction.START_SHADOW, releaseManager);

        LifecycleDecision denied = service.transition(STRATEGY,
                StrategyLifecycleAction.START_CANARY, releaseManager);

        assertThat(denied.reason()).isEqualTo(LifecycleReason.EXTERNAL_READINESS_BLOCKED);
        assertThat(denied.detail()).contains("release assignment is missing");
    }

    @Test
    void selfReportedResearchPassCannotReplaceRegistryProjection() throws Exception {
        service.registerDraft(signedArtifact("v1", "author", BASE.plus(Duration.ofDays(30))), author);
        byte[] forged = "self-reported PASS".getBytes(StandardCharsets.UTF_8);

        LifecycleDecision denied = service.recordEvidence(STRATEGY,
                new EvidenceSubmission(StrategyEvidenceType.RESEARCH, EvidenceOutcome.PASS,
                        forged, sha256(forged), "evidence://research/self-report",
                        BASE, BASE.plus(Duration.ofDays(1))), evidenceOfficer);

        assertThat(denied.reason()).isEqualTo(LifecycleReason.EVIDENCE_HASH_MISMATCH);
        assertThat(store.evidence).isEmpty();
    }

    @Test
    void expiredLiveEvidenceAutomaticallyDowngradesCanaryToShadow() throws Exception {
        toReview();
        for (StrategyEvidenceType type : StrategyEvidenceType.values()) {
            Duration ttl = baseEvidence().contains(type) ? Duration.ofHours(3) : Duration.ofHours(1);
            recordEvidence(type, ttl);
        }
        approveUntilApproved(BASE.plus(Duration.ofHours(4)));
        service.transition(STRATEGY, StrategyLifecycleAction.START_SHADOW, releaseManager);
        assignCurrentRelease(service.current(STRATEGY).artifactSha256());
        service.transition(STRATEGY, StrategyLifecycleAction.START_CANARY, releaseManager);

        clock.advance(Duration.ofMinutes(61));
        StrategyLifecycleState downgraded = service.current(STRATEGY);
        assertThat(downgraded.stage()).isEqualTo(StrategyLifecycleStage.SHADOW);
        assertThat(downgraded.updatedBy()).isEqualTo("strategy-policy-gate");
        assertThat(store.events.get(store.events.size() - 1)).satisfies(event -> {
            assertThat(event.action()).isEqualTo(StrategyLifecycleAction.POLICY_DOWNGRADE);
            assertThat(event.fromStage()).isEqualTo(StrategyLifecycleStage.CANARY);
            assertThat(event.toStage()).isEqualTo(StrategyLifecycleStage.SHADOW);
        });
    }

    @Test
    void expiredApprovalsAndArtifactAutomaticallyDowngradeWithoutAnOperatorBypass() throws Exception {
        service.registerDraft(signedArtifact("v1", "author", BASE.plus(Duration.ofHours(2))), author);
        service.transition(STRATEGY, StrategyLifecycleAction.SUBMIT_REVIEW, author);
        service.recordApproval(STRATEGY, ApprovalDecision.APPROVE, BASE.plus(Duration.ofMinutes(30)), reviewerOne);
        service.recordApproval(STRATEGY, ApprovalDecision.APPROVE, BASE.plus(Duration.ofMinutes(30)), reviewerTwo);
        service.transition(STRATEGY, StrategyLifecycleAction.APPROVE, releaseManager);

        clock.advance(Duration.ofMinutes(31));
        assertThat(service.current(STRATEGY).stage()).isEqualTo(StrategyLifecycleStage.REVIEW);

        clock.advance(Duration.ofHours(2));
        assertThat(service.current(STRATEGY).stage()).isEqualTo(StrategyLifecycleStage.DRAFT);
        assertThat(store.events).anyMatch(event -> event.reason() == LifecycleReason.ARTIFACT_EXPIRED);
    }

    @Test
    void rollbackRestoresOnlyAnImmutableArtifactOfTheSameStrategy() throws Exception {
        SignedStrategyArtifact v1 = signedArtifact("v1", "author", BASE.plus(Duration.ofDays(30)));
        service.registerDraft(v1, author);
        service.transition(STRATEGY, StrategyLifecycleAction.SUBMIT_REVIEW, author);
        service.rollback(STRATEGY, null, "replace candidate", releaseManager);

        SignedStrategyArtifact v2 = signedArtifact("v2", "author", BASE.plus(Duration.ofDays(30)));
        service.registerDraft(v2, author);
        service.transition(STRATEGY, StrategyLifecycleAction.SUBMIT_REVIEW, author);
        recordAllEvidence(Duration.ofDays(5));
        approveUntilApproved(BASE.plus(Duration.ofDays(2)));
        service.transition(STRATEGY, StrategyLifecycleAction.START_SHADOW, releaseManager);
        assignCurrentRelease(v2.artifactSha256());
        service.transition(STRATEGY, StrategyLifecycleAction.START_CANARY, releaseManager);
        service.transition(STRATEGY, StrategyLifecycleAction.PROMOTE, releaseManager);

        LifecycleDecision unauthorized = service.rollback(STRATEGY, v1.artifactSha256(), "attack",
                actor("observer"));
        assertThat(unauthorized.reason()).isEqualTo(LifecycleReason.UNAUTHORIZED);
        LifecycleDecision rollback = service.rollback(STRATEGY, v1.artifactSha256(), "regression",
                releaseManager);
        assertThat(rollback.stage()).isEqualTo(StrategyLifecycleStage.ROLLED_BACK);
        assertThat(rollback.artifactSha256()).isEqualTo(v1.artifactSha256());
        StrategyLifecycleState state = service.current(STRATEGY);
        assertThat(state.previousArtifactSha256()).isEqualTo(v2.artifactSha256());
        assertThat(new StrategyPromotionGate(service).allows(STRATEGY, v1.artifactSha256(),
                StrategyLifecycleStage.SHADOW)).isFalse();
    }

    @Test
    void contentAddressedEvidenceMismatchIsDeniedAndAudited() throws Exception {
        service.registerDraft(signedArtifact("v1", "author", BASE.plus(Duration.ofDays(30))), author);
        EvidenceSubmission forged = new EvidenceSubmission(StrategyEvidenceType.RESEARCH,
                EvidenceOutcome.PASS, "different".getBytes(StandardCharsets.UTF_8), sha256("claimed"),
                "evidence://research/forged", BASE, BASE.plus(Duration.ofDays(1)));

        LifecycleDecision denied = service.recordEvidence(STRATEGY, forged, evidenceOfficer);
        assertThat(denied.reason()).isEqualTo(LifecycleReason.EVIDENCE_HASH_MISMATCH);
        assertThat(store.evidence).isEmpty();
        assertThat(store.events.get(store.events.size() - 1).accepted()).isFalse();
    }

    private void toReview() throws Exception {
        service.registerDraft(signedArtifact("v1", "author", BASE.plus(Duration.ofDays(30))), author);
        service.transition(STRATEGY, StrategyLifecycleAction.SUBMIT_REVIEW, author);
    }

    private void approveUntilApproved(Instant expiry) {
        service.recordApproval(STRATEGY, ApprovalDecision.APPROVE, expiry, reviewerOne);
        service.recordApproval(STRATEGY, ApprovalDecision.APPROVE, expiry, reviewerTwo);
        LifecycleDecision decision = service.transition(STRATEGY, StrategyLifecycleAction.APPROVE, releaseManager);
        assertThat(decision.allowed()).as(decision.detail()).isTrue();
        assertThat(decision.stage()).isEqualTo(StrategyLifecycleStage.APPROVED);
    }

    private void recordAllEvidence(Duration ttl) {
        for (StrategyEvidenceType type : StrategyEvidenceType.values()) recordEvidence(type, ttl);
    }

    private void recordEvidence(StrategyEvidenceType type, Duration ttl) {
        byte[] bytes = ("evidence:" + type).getBytes(StandardCharsets.UTF_8);
        LifecycleDecision decision = service.recordEvidence(STRATEGY,
                new EvidenceSubmission(type, EvidenceOutcome.PASS, bytes, sha256(bytes),
                        "evidence://" + type.name().toLowerCase(), clock.instant(), clock.instant().plus(ttl)),
                evidenceOfficer);
        assertThat(decision.allowed()).as(decision.detail()).isTrue();
    }

    private void assignCurrentRelease(String artifactSha256) {
        GovernanceSubject subject = new GovernanceSubject("strategy:" + STRATEGY, "TEST",
                "paper-account", "a".repeat(40), sha256("build"),
                "sha256:" + sha256("container"), sha256("config"), sha256("dataset"),
                artifactSha256, "kms://test/release-key", "run-1", "policy-v1");
        LifecycleDecision decision = service.assignRelease(STRATEGY, subject,
                StrategyLifecycleStage.SHADOW, BASE.plus(Duration.ofHours(2)), releaseManager);
        assertThat(decision.allowed()).as(decision.detail()).isTrue();
    }

    private Set<StrategyEvidenceType> baseEvidence() {
        return EnumSet.of(StrategyEvidenceType.REPRODUCIBILITY, StrategyEvidenceType.RESEARCH,
                StrategyEvidenceType.PARITY, StrategyEvidenceType.RISK,
                StrategyEvidenceType.RECONCILIATION);
    }

    private SignedStrategyArtifact signedArtifact(String version, String submitter, Instant expiresAt)
            throws Exception {
        byte[] content = ("{\"strategy\":\"" + STRATEGY + "\",\"version\":\"" + version + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        String digest = sha256(content);
        SignedStrategyArtifact provisional = new SignedStrategyArtifact(STRATEGY, version, content, digest,
                "application/json", "artifact://" + STRATEGY + "/" + digest, "Ed25519",
                "release-key-1", Base64.getEncoder().encodeToString(new byte[64]),
                BASE.minusSeconds(1), expiresAt, submitter, BASE, "registration-1", sha256("registration-1"));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(provisional.signaturePayload());
        return copy(provisional, content, digest, Base64.getEncoder().encodeToString(signer.sign()));
    }

    private SignedStrategyArtifact copy(SignedStrategyArtifact source, byte[] content,
                                        String digest, String signature) {
        return new SignedStrategyArtifact(source.strategyName(), source.artifactVersion(), content, digest,
                source.mediaType(), source.artifactUri(), source.signatureAlgorithm(), source.signingKeyId(),
                signature, source.signedAt(), source.expiresAt(), source.submittedBy(), source.submittedAt(),
                source.researchRegistrationId(), source.researchRegistrationSha256());
    }

    private StrategyArtifactSignatureVerifier verifierForCurrentKey() {
        TrustedSigningKey key = new TrustedSigningKey("release-key-1", "Ed25519",
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()), "ACTIVE",
                BASE.minus(Duration.ofDays(1)), BASE.plus(Duration.ofDays(365)), null);
        return new Ed25519StrategyArtifactSignatureVerifier(id -> Optional.of(key));
    }

    private void assertAuditChainIsValid() {
        Map<String, List<StrategyLifecycleEvent>> byStrategy = store.events.stream()
                .collect(Collectors.groupingBy(StrategyLifecycleEvent::strategyName,
                        LinkedHashMap::new, Collectors.toList()));
        byStrategy.values().forEach(events -> {
            String previous = LifecycleAuditHasher.GENESIS;
            long sequence = 1;
            for (StrategyLifecycleEvent event : events) {
                assertThat(event.sequenceNo()).isEqualTo(sequence++);
                assertThat(event.previousEventSha256()).isEqualTo(previous);
                assertThat(event.eventSha256()).isEqualTo(LifecycleAuditHasher.hash(event));
                previous = event.eventSha256();
            }
        });
    }

    private static LifecycleActor actor(String name, LifecyclePermission... permissions) {
        return new LifecycleActor(name, Set.of(permissions));
    }

    private static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    private static final class InMemoryStore implements StrategyLifecycleStore {
        private final Map<String, SignedStrategyArtifact> artifacts = new HashMap<>();
        private final Map<String, StrategyLifecycleState> states = new HashMap<>();
        private final List<StrategyApproval> approvals = new ArrayList<>();
        private final List<StrategyEvidenceSnapshot> evidence = new ArrayList<>();
        private final List<StrategyLifecycleEvent> events = new ArrayList<>();
        private final Map<String, StrategyReleaseAssignment> assignments = new HashMap<>();

        @Override
        public void insertArtifact(SignedStrategyArtifact artifact) {
            if (artifacts.putIfAbsent(artifact.artifactSha256(), artifact) != null) {
                throw new IllegalStateException("duplicate artifact");
            }
        }

        @Override
        public Optional<SignedStrategyArtifact> findArtifact(String artifactSha256) {
            return Optional.ofNullable(artifacts.get(artifactSha256));
        }

        @Override
        public Optional<StrategyLifecycleState> lockState(String strategyName) {
            return Optional.ofNullable(states.get(strategyName));
        }

        @Override
        public void insertState(StrategyLifecycleState state) {
            if (states.putIfAbsent(state.strategyName(), state) != null) throw new IllegalStateException("duplicate state");
        }

        @Override
        public boolean updateState(StrategyLifecycleState state, long expectedVersion) {
            StrategyLifecycleState current = states.get(state.strategyName());
            if (current == null || current.stateVersion() != expectedVersion) return false;
            states.put(state.strategyName(), state);
            return true;
        }

        @Override
        public void insertApproval(StrategyApproval approval) {
            approvals.add(approval);
        }

        @Override
        public List<StrategyApproval> approvals(String strategyName, String artifactSha256, long reviewCycle) {
            return approvals.stream().filter(item -> item.strategyName().equals(strategyName)
                    && item.artifactSha256().equals(artifactSha256) && item.reviewCycle() == reviewCycle).toList();
        }

        @Override
        public void insertEvidence(StrategyEvidenceSnapshot snapshot) {
            evidence.add(snapshot);
        }

        @Override
        public List<StrategyEvidenceSnapshot> evidence(String strategyName, String artifactSha256) {
            return evidence.stream().filter(item -> item.strategyName().equals(strategyName)
                    && item.artifactSha256().equals(artifactSha256)).toList();
        }

        @Override
        public Optional<StrategyLifecycleEvent> latestEvent(String strategyName) {
            return events.stream().filter(item -> item.strategyName().equals(strategyName))
                    .max(java.util.Comparator.comparingLong(StrategyLifecycleEvent::sequenceNo));
        }

        @Override
        public void appendEvent(StrategyLifecycleEvent event) {
            events.add(event);
        }

        @Override
        public Optional<StrategyReleaseAssignment> lockReleaseAssignment(String strategyName) {
            return Optional.ofNullable(assignments.get(strategyName));
        }

        @Override
        public Optional<StrategyReleaseAssignment> findReleaseAssignment(String strategyName) {
            return Optional.ofNullable(assignments.get(strategyName));
        }

        @Override
        public void replaceReleaseAssignment(StrategyReleaseAssignment assignment, long expectedVersion) {
            StrategyReleaseAssignment current = assignments.get(assignment.strategyName());
            long actual = current == null ? -1L : current.assignmentVersion();
            if (actual != expectedVersion) throw new IllegalStateException("CONCURRENT_UPDATE");
            assignments.put(assignment.strategyName(), assignment);
        }
    }
}
