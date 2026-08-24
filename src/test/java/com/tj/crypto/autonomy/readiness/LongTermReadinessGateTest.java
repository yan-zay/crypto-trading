package com.tj.crypto.autonomy.readiness;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LongTermReadinessGateTest {
    private static final Instant NOW = Instant.parse("2026-08-24T08:00:00Z");
    private static final String SUBJECT_SHA = hash("subject");
    private static final String RELEASE_SHA = hash("release");
    private static final String POLICY_SHA = hash("policy");
    private static final String AGENT_BUNDLE_SHA = hash("agent-bundle");
    private static final String LEGAL_SCOPE_SHA = hash("legal-scope");

    @Test
    void completeExternalEvidenceProducesIndependentL3AndL4GoDecisions() {
        LongTermReadinessSubject subject = subject();
        LongTermReadinessGate gate = trustedGate((candidate, level) -> references(level));

        LongTermReadinessAssessment assessment = gate.evaluateBoth(subject, NOW);

        assertThat(assessment.l3Advisory().readyAt(NOW)).isTrue();
        assertThat(assessment.l4BoundedAutonomy().readyAt(NOW)).isTrue();
        assertThat(assessment.l3Advisory().acceptedEvidence())
                .hasSize(LongTermControl.values().length);
        assertThat(assessment.l4BoundedAutonomy().acceptedEvidence())
                .hasSize(LongTermControl.values().length);
        assertThat(assessment.l3Advisory().readinessSubjectSha256())
                .isEqualTo(subject.sha256());
        assertThat(assessment.l3Advisory().evidenceSetSha256())
                .isNotEqualTo(assessment.l4BoundedAutonomy().evidenceSetSha256());
        assertThat(assessment.l4BoundedAutonomy().expiresAt())
                .isEqualTo(NOW.plus(Duration.ofHours(24)).minusSeconds(1));
    }

    @Test
    void l4RequiresAnIndependentGoDecisionForTheSameL3Subject() {
        ReadinessEvidenceSource source = (candidate, level) ->
                level == AutonomyLevel.L3_ADVISORY ? List.of() : references(level);
        LongTermReadinessDecision decision = trustedGate(source).evaluateL4(subject(), NOW);

        assertThat(decision.readyAt(NOW)).isFalse();
        assertThat(decision.acceptedEvidence()).hasSize(LongTermControl.values().length);
        assertThat(decision.blockers())
                .contains("L3_ADVISORY: prerequisite decision is NO_GO or expired");
    }

    @Test
    void l4BindsTheL3DecisionUsesItsShortestTtlAndExpiresFailClosed() {
        LongTermReadinessGate gate = new LongTermReadinessGate(
                (candidate, level) -> references(level),
                (reference, candidate, level, now) -> claims(reference, candidate, level,
                        binding(candidate), level, VerificationOutcome.PASS,
                        level == AutonomyLevel.L3_ADVISORY
                                ? NOW.plus(Duration.ofMinutes(1))
                                : NOW.plus(Duration.ofDays(7)),
                        receipt(reference), reference.declaredContentSha256(),
                        validPayload(reference.control(), level, candidate)));

        LongTermReadinessDecision decision = gate.evaluateL4(subject(), NOW);

        assertThat(decision.readyAt(NOW)).isTrue();
        assertThat(decision.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
        assertThat(decision.prerequisiteDecisionSha256()).isNotEqualTo("0".repeat(64));
        assertThat(decision.readyAt(decision.expiresAt())).isFalse();
        assertThat(decision.wasGoAtDecisionTime()).isTrue();
    }

    @Test
    void replacingL3EvidenceChangesTheDerivedL4EvidenceSetDigest() {
        LongTermReadinessDecision first = trustedGate(
                (candidate, level) -> references(level)).evaluateL4(subject(), NOW);
        ReadinessEvidenceSource revisedSource = (candidate, level) -> {
            List<ExternalEvidenceReference> current = new ArrayList<>(references(level));
            if (level == AutonomyLevel.L3_ADVISORY) {
                ExternalEvidenceReference old = current.get(0);
                current.set(0, new ExternalEvidenceReference("revised-" + old.evidenceId(),
                        old.control(), URI.create("evidence://store/revised/" + old.control()),
                        hash("revised-l3-content")));
            }
            return List.copyOf(current);
        };
        LongTermReadinessDecision revised = trustedGate(revisedSource).evaluateL4(subject(), NOW);

        assertThat(first.readyAt(NOW)).isTrue();
        assertThat(revised.readyAt(NOW)).isTrue();
        assertThat(revised.prerequisiteDecisionSha256())
                .isNotEqualTo(first.prerequisiteDecisionSha256());
        assertThat(revised.evidenceSetSha256()).isNotEqualTo(first.evidenceSetSha256());
    }

    @Test
    void l3AndL4CannotReuseTheSameExternalEvidenceOrVerifierReceipt() {
        List<ExternalEvidenceReference> shared = references(AutonomyLevel.L3_ADVISORY);
        LongTermReadinessDecision decision = trustedGate(
                (candidate, level) -> shared).evaluateL4(subject(), NOW);

        assertThat(decision.readyAt(NOW)).isFalse();
        assertThat(decision.blockers()).contains(
                "EVIDENCE_SET: evidence ID reused across L3/L4",
                "EVIDENCE_SET: verified content reused across L3/L4",
                "EVIDENCE_SET: artifact URI reused across L3/L4",
                "EVIDENCE_SET: verifier receipt reused across L3/L4");
    }

    @Test
    void defaultVerifierAlwaysRejectsEvenWhenCallerSuppliesEveryReference() {
        LongTermReadinessGate gate = new LongTermReadinessGate(
                (candidate, level) -> references(level));

        LongTermReadinessDecision decision = gate.evaluateL3(subject(), NOW);

        assertThat(decision.readyAt(NOW)).isFalse();
        assertThat(decision.blockers()).allMatch(reason ->
                reason.contains("NOT_VERIFIED"));
        assertThat(decision.acceptedEvidence()).isEmpty();
    }

    @Test
    void everyDeclaredControlIsMandatoryForBothAutonomyLevels() {
        for (AutonomyLevel level : AutonomyLevel.values()) {
            for (LongTermControl missing : LongTermControl.values()) {
                List<ExternalEvidenceReference> incomplete = references(level).stream()
                        .filter(reference -> reference.control() != missing)
                        .toList();
                LongTermReadinessDecision decision = trustedGate(
                        (candidate, ignored) -> incomplete).evaluate(subject(), level, NOW);

                assertThat(decision.readyAt(NOW))
                        .as("%s must require %s", level, missing).isFalse();
                assertThat(decision.blockers())
                        .anyMatch(reason -> reason.equals(missing + ": NOT_VERIFIED"));
            }
        }
    }

    @Test
    void duplicateControlIdContentAndVerifierReceiptAllFailClosed() {
        AutonomyLevel level = AutonomyLevel.L4_BOUNDED_AUTONOMY;
        List<ExternalEvidenceReference> duplicateControl = new ArrayList<>(references(level));
        duplicateControl.add(reference("extra-legal", LongTermControl.LEGAL, level,
                hash("extra-legal")));
        assertThat(trustedGate((candidate, ignored) -> duplicateControl)
                .evaluateL4(subject(), NOW).readyAt(NOW)).isFalse();

        List<ExternalEvidenceReference> duplicateId = new ArrayList<>(references(level));
        ExternalEvidenceReference first = duplicateId.get(0);
        ExternalEvidenceReference second = duplicateId.get(1);
        duplicateId.set(1, new ExternalEvidenceReference(first.evidenceId(), second.control(),
                second.artifactUri(), second.declaredContentSha256()));
        assertThat(trustedGate((candidate, ignored) -> duplicateId)
                .evaluateL4(subject(), NOW).blockers())
                .contains("EVIDENCE_SET: duplicate evidence ID");

        List<ExternalEvidenceReference> duplicateContent = new ArrayList<>(references(level));
        first = duplicateContent.get(0);
        second = duplicateContent.get(1);
        duplicateContent.set(1, new ExternalEvidenceReference(second.evidenceId(), second.control(),
                second.artifactUri(), first.declaredContentSha256()));
        assertThat(trustedGate((candidate, ignored) -> duplicateContent)
                .evaluateL4(subject(), NOW).blockers())
                .contains("EVIDENCE_SET: duplicate evidence content");

        List<ExternalEvidenceReference> duplicateUri = new ArrayList<>(references(level));
        first = duplicateUri.get(0);
        second = duplicateUri.get(1);
        duplicateUri.set(1, new ExternalEvidenceReference(second.evidenceId(), second.control(),
                first.artifactUri(), second.declaredContentSha256()));
        assertThat(trustedGate((candidate, ignored) -> duplicateUri)
                .evaluateL4(subject(), NOW).blockers())
                .contains("EVIDENCE_SET: duplicate evidence artifact URI");

        ExternalReadinessEvidenceVerifier repeatedReceipt = (reference, candidate, requested, now) ->
                claims(reference, candidate, requested, binding(candidate), requested,
                        VerificationOutcome.PASS, NOW.plus(Duration.ofDays(7)),
                        "same-receipt", reference.declaredContentSha256(),
                        validPayload(reference.control(), requested, candidate));
        LongTermReadinessDecision receiptDecision = new LongTermReadinessGate(
                (candidate, ignored) -> references(level), repeatedReceipt)
                .evaluateL4(subject(), NOW);
        assertThat(receiptDecision.readyAt(NOW)).isFalse();
        assertThat(receiptDecision.blockers())
                .contains("EVIDENCE_SET: duplicate verifier receipt");
    }

    @Test
    void failUnknownExpiredAndVerifierFailureNeverProduceGo() {
        for (VerificationOutcome outcome : List.of(
                VerificationOutcome.FAIL, VerificationOutcome.UNKNOWN)) {
            LongTermReadinessDecision decision = gateWithOverride((reference, candidate, level) ->
                    reference.control() == LongTermControl.LEGAL
                            ? claims(reference, candidate, level, binding(candidate), level,
                            outcome, NOW.plus(Duration.ofDays(7)), receipt(reference),
                            reference.declaredContentSha256(),
                            validPayload(reference.control(), level, candidate))
                            : verified(reference, candidate, level)).evaluateL4(subject(), NOW);
            assertThat(decision.readyAt(NOW)).as(outcome.toString()).isFalse();
            assertThat(decision.blockers()).anyMatch(reason -> reason.contains(outcome.toString()));
        }

        LongTermReadinessDecision expired = gateWithOverride((reference, candidate, level) ->
                reference.control() == LongTermControl.LEGAL
                        ? claims(reference, candidate, level, binding(candidate), level,
                        VerificationOutcome.PASS, NOW, receipt(reference),
                        reference.declaredContentSha256(),
                        validPayload(reference.control(), level, candidate))
                        : verified(reference, candidate, level)).evaluateL4(subject(), NOW);
        assertThat(expired.readyAt(NOW)).isFalse();
        assertThat(expired.blockers()).anyMatch(reason -> reason.contains("expired"));

        LongTermReadinessDecision verifierFailure = new LongTermReadinessGate(
                (candidate, level) -> references(level),
                (reference, candidate, level, now) -> {
                    throw new IllegalStateException("secret-bearing failure");
                }).evaluateL4(subject(), NOW);
        assertThat(verifierFailure.readyAt(NOW)).isFalse();
        assertThat(verifierFailure.blockers()).allMatch(reason ->
                !reason.contains("secret-bearing"));
    }

    @Test
    void observedAtAndVerifiedAtAreIndependentlyRejectedWhenFuture() {
        LongTermReadinessDecision observedFuture = gateWithOverride(
                (reference, candidate, level) -> reference.control() == LongTermControl.LEGAL
                        ? claimsAt(reference, candidate, level, NOW.plusSeconds(1),
                        NOW.plusSeconds(2))
                        : verified(reference, candidate, level)).evaluateL4(subject(), NOW);
        assertThat(observedFuture.readyAt(NOW)).isFalse();
        assertThat(observedFuture.blockers())
                .anyMatch(reason -> reason.contains("observedAt is in the future"));

        LongTermReadinessDecision verifiedFuture = gateWithOverride(
                (reference, candidate, level) -> reference.control() == LongTermControl.LEGAL
                        ? claimsAt(reference, candidate, level,
                        NOW.minus(Duration.ofDays(1)), NOW.plusSeconds(1))
                        : verified(reference, candidate, level)).evaluateL4(subject(), NOW);
        assertThat(verifiedFuture.readyAt(NOW)).isFalse();
        assertThat(verifiedFuture.blockers())
                .anyMatch(reason -> reason.contains("verifiedAt is in the future"));
    }

    @Test
    void exactVerificationFreshnessBoundaryReturnsNoGoInsteadOfThrowing() {
        LongTermReadinessDecision decision = gateWithOverride(
                (reference, candidate, level) -> reference.control() == LongTermControl.LEGAL
                        ? claimsAt(reference, candidate, level,
                        NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofHours(24)))
                        : verified(reference, candidate, level)).evaluateL4(subject(), NOW);

        assertThat(decision.readyAt(NOW)).isFalse();
        assertThat(decision.blockers())
                .anyMatch(reason -> reason.contains("external verification is stale"));
    }

    @Test
    void sourceAndVerifierNullFailuresAndExplicitRejectionsAllFailClosed() {
        List<ReadinessEvidenceSource> invalidSources = List.of(
                (candidate, level) -> null,
                (candidate, level) -> {
                    throw new IllegalStateException("unavailable");
                },
                (candidate, level) -> {
                    List<ExternalEvidenceReference> withNull = new ArrayList<>(references(level));
                    withNull.add(null);
                    return withNull;
                });
        for (ReadinessEvidenceSource source : invalidSources) {
            LongTermReadinessDecision decision = trustedGate(source).evaluateL3(subject(), NOW);
            assertThat(decision.readyAt(NOW)).isFalse();
        }

        for (EvidenceVerification invalid : List.of(
                RejectedEvidenceVerification.notVerified("fixture rejection"))) {
            LongTermReadinessDecision decision = new LongTermReadinessGate(
                    (candidate, level) -> references(level),
                    (reference, candidate, level, now) ->
                            reference.control() == LongTermControl.LEGAL
                                    ? invalid : verified(reference, candidate, level))
                    .evaluateL3(subject(), NOW);
            assertThat(decision.readyAt(NOW)).isFalse();
            assertThat(decision.acceptedEvidence()).doesNotContainKey(LongTermControl.LEGAL);
        }
        LongTermReadinessDecision nullVerification = new LongTermReadinessGate(
                (candidate, level) -> references(level),
                (reference, candidate, level, now) ->
                        reference.control() == LongTermControl.LEGAL
                                ? null : verified(reference, candidate, level))
                .evaluateL3(subject(), NOW);
        assertThat(nullVerification.readyAt(NOW)).isFalse();
        assertThat(nullVerification.acceptedEvidence()).doesNotContainKey(LongTermControl.LEGAL);
    }

    @Test
    void allCommonClaimsBindSubjectReleaseAccountEnvironmentPolicyAndEnvelope() {
        for (BindingField field : BindingField.values()) {
            LongTermReadinessDecision decision = gateWithOverride((reference, candidate, level) ->
                    reference.control() == LongTermControl.LEGAL
                            ? claims(reference, candidate, level,
                            mismatchedBinding(candidate, field), level,
                            VerificationOutcome.PASS, NOW.plus(Duration.ofDays(7)),
                            receipt(reference), reference.declaredContentSha256(),
                            validPayload(reference.control(), level, candidate))
                            : verified(reference, candidate, level)).evaluateL4(subject(), NOW);

            assertThat(decision.readyAt(NOW)).as(field.toString()).isFalse();
            assertThat(decision.blockers()).as(field.toString())
                    .anyMatch(reason -> reason.contains("signed claim mismatch"));
        }
    }

    @Test
    void evidenceIdentitySchemaAudienceArtifactContentAndPayloadTypeAreAllBound() {
        for (CommonClaimField field : CommonClaimField.values()) {
            LongTermReadinessDecision decision = gateWithOverride(
                    (reference, candidate, level) -> reference.control() == LongTermControl.LEGAL
                            ? mismatchedCommonClaims(reference, candidate, level, field)
                            : verified(reference, candidate, level)).evaluateL4(subject(), NOW);

            assertThat(decision.readyAt(NOW)).as(field.toString()).isFalse();
            assertThat(decision.acceptedEvidence()).as(field.toString())
                    .doesNotContainKey(LongTermControl.LEGAL);
        }
    }

    @Test
    void l3ClaimsCannotBeReusedForL4AndApprovalModesAreLevelSpecific() {
        ExternalReadinessEvidenceVerifier l3Only = (reference, candidate, requested, now) ->
                claims(reference, candidate, requested, binding(candidate),
                        AutonomyLevel.L3_ADVISORY, VerificationOutcome.PASS,
                        NOW.plus(Duration.ofDays(7)), receipt(reference),
                        reference.declaredContentSha256(),
                        validPayload(reference.control(), AutonomyLevel.L3_ADVISORY, candidate));
        LongTermReadinessDecision reused = new LongTermReadinessGate(
                (candidate, level) -> references(level), l3Only).evaluateL4(subject(), NOW);
        assertThat(reused.readyAt(NOW)).isFalse();
        assertThat(reused.blockers()).anyMatch(reason ->
                reason.contains("autonomyLevel"));

        LongTermReadinessDecision wrongApproval = gateWithOverride(
                (reference, candidate, level) -> reference.control()
                        == LongTermControl.AGENT_ASSURANCE
                        ? claims(reference, candidate, level, binding(candidate), level,
                        VerificationOutcome.PASS, NOW.plus(Duration.ofDays(7)),
                        receipt(reference), reference.declaredContentSha256(),
                        validPayload(reference.control(), AutonomyLevel.L3_ADVISORY, candidate))
                        : verified(reference, candidate, level)).evaluateL4(subject(), NOW);
        assertThat(wrongApproval.readyAt(NOW)).isFalse();
        assertThat(wrongApproval.blockers()).anyMatch(reason ->
                reason.contains("approval mode"));
    }

    @Test
    void everyTypedControlPayloadHasIndependentFailClosedSemantics() {
        for (LongTermControl invalid : LongTermControl.values()) {
            for (VerifiedControlPayload invalidPayload
                    : invalidPayloadVariants(invalid, AutonomyLevel.L4_BOUNDED_AUTONOMY,
                    subject())) {
                LongTermReadinessDecision decision = gateWithOverride(
                        (reference, candidate, level) -> reference.control() == invalid
                                ? claims(reference, candidate, level, binding(candidate), level,
                                VerificationOutcome.PASS, NOW.plus(Duration.ofDays(7)),
                                receipt(reference), reference.declaredContentSha256(),
                                invalidPayload)
                                : verified(reference, candidate, level))
                        .evaluateL4(subject(), NOW);

                assertThat(decision.readyAt(NOW)).as(invalid.toString()).isFalse();
                assertThat(decision.acceptedEvidence()).as(invalid.toString())
                        .doesNotContainKey(invalid);
            }
        }
    }

    @Test
    void missingSubjectLevelOrTimeAndInactiveEnvelopeReturnNoGoWithoutCallingSystems() {
        LongTermReadinessGate gate = new LongTermReadinessGate();
        assertThat(gate.evaluate(null, AutonomyLevel.L3_ADVISORY, NOW).readyAt(NOW)).isFalse();
        assertThat(gate.evaluate(subject(), null, NOW).readyAt(NOW)).isFalse();
        assertThat(gate.evaluate(subject(), AutonomyLevel.L3_ADVISORY, null).readyAt(NOW)).isFalse();

        LongTermReadinessSubject expired = new LongTermReadinessSubject(SUBJECT_SHA,
                "release-1", RELEASE_SHA, "BINANCE:owner-subaccount", "LIVE",
                "risk-v4", POLICY_SHA, AGENT_BUNDLE_SHA, LEGAL_SCOPE_SHA,
                new AutonomyEnvelope("BINANCE", "PERPETUAL", Set.of("BTCUSDT"),
                NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofDays(1)),
                "USDT", new BigDecimal("1000"), new BigDecimal("50"),
                Duration.ofMinutes(1)));
        AtomicInteger externalCalls = new AtomicInteger();
        LongTermReadinessDecision decision = new LongTermReadinessGate((candidate, level) -> {
            externalCalls.incrementAndGet();
            return references(level);
        }, (reference, candidate, level, now) -> {
            externalCalls.incrementAndGet();
            return verified(reference, candidate, level);
        }).evaluateL4(expired, NOW);
        assertThat(decision.readyAt(NOW)).isFalse();
        assertThat(decision.blockers()).contains("AUTONOMY_ENVELOPE: inactive or expired");
        assertThat(externalCalls).hasValue(0);
    }

    @Test
    void subMillisecondTtlChangesTheEnvelopeAndSubjectDigest() {
        AutonomyEnvelope first = new AutonomyEnvelope("BINANCE", "PERPETUAL",
                Set.of("BTCUSDT"), NOW, NOW.plusSeconds(1), "USDT", BigDecimal.ONE,
                BigDecimal.ONE, Duration.ofNanos(1));
        AutonomyEnvelope second = new AutonomyEnvelope("BINANCE", "PERPETUAL",
                Set.of("BTCUSDT"), NOW, NOW.plusSeconds(1), "USDT", BigDecimal.ONE,
                BigDecimal.ONE, Duration.ofNanos(2));

        assertThat(first.sha256()).isNotEqualTo(second.sha256());
        LongTermReadinessSubject subjectOne = new LongTermReadinessSubject(SUBJECT_SHA,
                "release-1", RELEASE_SHA, "account", "LIVE", "policy", POLICY_SHA,
                AGENT_BUNDLE_SHA, LEGAL_SCOPE_SHA, first);
        LongTermReadinessSubject subjectTwo = new LongTermReadinessSubject(SUBJECT_SHA,
                "release-1", RELEASE_SHA, "account", "LIVE", "policy", POLICY_SHA,
                AGENT_BUNDLE_SHA, LEGAL_SCOPE_SHA, second);
        assertThat(subjectOne.sha256()).isNotEqualTo(subjectTwo.sha256());
    }

    @Test
    void capabilityTtlCannotExceedTheDeterministicTwoMinuteCeiling() {
        new AutonomyEnvelope("BINANCE", "PERPETUAL", Set.of("BTCUSDT"), NOW,
                NOW.plus(Duration.ofDays(1)), "USDT", BigDecimal.ONE, BigDecimal.ONE,
                Duration.ofMinutes(2));

        assertThatThrownBy(() -> new AutonomyEnvelope("BINANCE", "PERPETUAL",
                Set.of("BTCUSDT"), NOW, NOW.plus(Duration.ofDays(1)), "USDT",
                BigDecimal.ONE, BigDecimal.ONE, Duration.ofMinutes(2).plusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("two minutes");
    }

    private LongTermReadinessGate trustedGate(ReadinessEvidenceSource source) {
        return new LongTermReadinessGate(source,
                (reference, candidate, level, now) -> verified(reference, candidate, level));
    }

    private LongTermReadinessGate gateWithOverride(VerifierOverride override) {
        return new LongTermReadinessGate((candidate, level) -> references(level),
                (reference, candidate, level, now) ->
                        override.verify(reference, candidate, level));
    }

    private VerifiedReadinessClaims verified(ExternalEvidenceReference reference,
                                             LongTermReadinessSubject candidate,
                                             AutonomyLevel level) {
        return claims(reference, candidate, level, binding(candidate), level,
                VerificationOutcome.PASS, NOW.plus(Duration.ofDays(7)), receipt(reference),
                reference.declaredContentSha256(),
                validPayload(reference.control(), level, candidate));
    }

    private VerifiedReadinessClaims claimsAt(ExternalEvidenceReference reference,
                                             LongTermReadinessSubject candidate,
                                             AutonomyLevel level,
                                             Instant observedAt,
                                             Instant verifiedAt) {
        return new VerifiedReadinessClaims(reference.evidenceId(), level,
                schema(reference.control()), "crypto-trading-long-term-readiness-v1",
                candidate.subjectSha256(), candidate.sha256(), candidate.releaseId(),
                candidate.releaseArtifactSha256(), candidate.accountScope(),
                candidate.environment(), candidate.policyVersion(),
                candidate.policyArtifactSha256(), candidate.agentBundleSha256(),
                candidate.legalScopeSha256(),
                candidate.envelope().sha256(),
                reference.artifactUri(), observedAt, NOW.plus(Duration.ofDays(7)),
                VerificationOutcome.PASS, reference.declaredContentSha256(), "verifier-1",
                receipt(reference), "governance-ca", "kms://governance/key/1",
                SignatureAlgorithm.ED25519, verifiedAt,
                validPayload(reference.control(), level, candidate));
    }

    private VerifiedReadinessClaims claims(ExternalEvidenceReference reference,
                                           LongTermReadinessSubject candidate,
                                           AutonomyLevel requestedLevel,
                                           Binding binding,
                                           AutonomyLevel claimedLevel,
                                           VerificationOutcome outcome,
                                           Instant expiresAt,
                                           String receipt,
                                           String contentSha256,
                                           VerifiedControlPayload payload) {
        return new VerifiedReadinessClaims(reference.evidenceId(), claimedLevel,
                schema(reference.control()), "crypto-trading-long-term-readiness-v1",
                binding.subjectSha256(), binding.readinessSubjectSha256(), binding.releaseId(),
                binding.releaseArtifactSha256(), binding.accountScope(), binding.environment(),
                binding.policyVersion(), binding.policyArtifactSha256(),
                binding.agentBundleSha256(), binding.legalScopeSha256(),
                binding.envelopeSha256(), reference.artifactUri(),
                NOW.minus(Duration.ofDays(1)), expiresAt, outcome, contentSha256,
                "verifier-1", receipt, "governance-ca", "kms://governance/key/1",
                SignatureAlgorithm.ED25519, NOW.minusSeconds(1), payload);
    }

    private VerifiedReadinessClaims mismatchedCommonClaims(
            ExternalEvidenceReference reference,
            LongTermReadinessSubject candidate,
            AutonomyLevel level,
            CommonClaimField field) {
        VerifiedControlPayload payload = field == CommonClaimField.PAYLOAD_CONTROL
                ? validPayload(LongTermControl.RESEARCH_ALPHA, level, candidate)
                : validPayload(reference.control(), level, candidate);
        return new VerifiedReadinessClaims(
                field == CommonClaimField.EVIDENCE_ID ? "other-evidence" : reference.evidenceId(),
                level,
                field == CommonClaimField.SCHEMA_VERSION
                        ? "crypto-trading/long-term-readiness/wrong/v1"
                        : schema(reference.control()),
                field == CommonClaimField.AUDIENCE ? "wrong-audience"
                        : "crypto-trading-long-term-readiness-v1",
                candidate.subjectSha256(), candidate.sha256(), candidate.releaseId(),
                candidate.releaseArtifactSha256(), candidate.accountScope(),
                candidate.environment(), candidate.policyVersion(),
                candidate.policyArtifactSha256(), candidate.agentBundleSha256(),
                candidate.legalScopeSha256(), candidate.envelope().sha256(),
                field == CommonClaimField.ARTIFACT_URI
                        ? URI.create("evidence://store/other-artifact") : reference.artifactUri(),
                NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(7)),
                VerificationOutcome.PASS,
                field == CommonClaimField.CONTENT_SHA256
                        ? hash("other-content") : reference.declaredContentSha256(),
                "verifier-1", receipt(reference), "governance-ca", "kms://governance/key/1",
                SignatureAlgorithm.ED25519, NOW.minusSeconds(1), payload);
    }

    private VerifiedControlPayload validPayload(LongTermControl control,
                                                AutonomyLevel level,
                                                LongTermReadinessSubject candidate) {
        String h = hash("payload-" + control);
        return switch (control) {
            case LEGAL -> new VerifiedControlPayload.Legal("owner-entity", "approved-jurisdiction",
                    candidate.legalScopeSha256(), h,
                    NOW.minus(Duration.ofDays(90)), NOW.plus(Duration.ofDays(20)),
                    VerifiedControlPayload.DeploymentMatch.MATCH);
            case REPRODUCIBILITY -> new VerifiedControlPayload.Reproducibility("a".repeat(40),
                    h, "sha256:" + hash("image"), h, h, 42L, "model-v1", h,
                    candidate.agentBundleSha256(),
                    VerifiedControlPayload.Result.PASS, VerifiedControlPayload.Result.PASS,
                    VerifiedControlPayload.Result.PASS);
            case RESEARCH_ALPHA -> new VerifiedControlPayload.ResearchAlpha(h, h, h, h,
                    new BigDecimal("0.012"), 30, 8,
                    VerifiedControlPayload.Result.PASS, VerifiedControlPayload.Result.PASS,
                    VerifiedControlPayload.Result.PASS, VerifiedControlPayload.Result.PASS,
                    VerifiedControlPayload.Result.PASS);
            case EXECUTION_CONSISTENCY ->
                    new VerifiedControlPayload.ExecutionConsistency(
                            h, 100, 100, 100, 0, 0, 0);
            case RISK_CONTROLS -> new VerifiedControlPayload.RiskControls(
                    candidate.policyArtifactSha256(), h,
                    VerifiedControlPayload.Result.PASS, VerifiedControlPayload.Result.PASS,
                    VerifiedControlPayload.Result.PASS, VerifiedControlPayload.Result.PASS,
                    VerifiedControlPayload.Result.PASS);
            case RECONCILIATION -> new VerifiedControlPayload.Reconciliation(
                    h, 100, 0, 0, 0, 0, 0);
            case SECURITY -> new VerifiedControlPayload.Security(h, h, h, h,
                    VerifiedControlPayload.Result.PASS, VerifiedControlPayload.Result.PASS,
                    VerifiedControlPayload.Result.PASS, VerifiedControlPayload.Result.PASS,
                    100, 0, 0);
            case OPERATIONS -> new VerifiedControlPayload.Operations(h, h, h,
                    VerifiedControlPayload.Result.PASS, VerifiedControlPayload.Result.PASS,
                    VerifiedControlPayload.Result.PASS,
                    Duration.ofDays(7), 20, 0);
            case AGENT_ASSURANCE -> new VerifiedControlPayload.AgentAssurance(h, h, h, h,
                    candidate.agentBundleSha256(),
                    level == AutonomyLevel.L3_ADVISORY
                            ? VerifiedControlPayload.ApprovalMode.PER_RECOMMENDATION
                            : VerifiedControlPayload.ApprovalMode.PREAPPROVED_ENVELOPE,
                    VerifiedControlPayload.GatewayAccess.NONE,
                    VerifiedControlPayload.Result.PASS, VerifiedControlPayload.Result.PASS,
                    1000, 0, 0);
            case CAPITAL_CONTROLS -> new VerifiedControlPayload.CapitalControls(
                    candidate.accountScope(), candidate.policyArtifactSha256(),
                    VerifiedControlPayload.AccountIsolation.ISOLATED_SUBACCOUNT,
                    VerifiedControlPayload.WithdrawalPermission.PROHIBITED,
                    candidate.envelope().valuationCurrency(),
                    candidate.envelope().maxGrossNotional(), candidate.envelope().maxDailyLoss(),
                    candidate.envelope().capabilityTtl());
            case OWNER_VENUE_CONTRACT -> new VerifiedControlPayload.OwnerVenueContract(
                    candidate.envelope().venue(), candidate.accountScope(), h, 50, 0);
            case CROSS_MARKET_STATE -> new VerifiedControlPayload.CrossMarketState(h,
                    java.util.Map.of(VerifiedControlPayload.MarketRegime.TRENDING, 100L,
                            VerifiedControlPayload.MarketRegime.HIGH_VOLATILITY, 100L,
                            VerifiedControlPayload.MarketRegime.STRESS, 100L),
                    NOW.minus(Duration.ofDays(120)), NOW.minus(Duration.ofDays(2)), 100, 0);
        };
    }

    private VerifiedControlPayload invalidPayload(LongTermControl control,
                                                  AutonomyLevel level,
                                                  LongTermReadinessSubject candidate) {
        String h = hash("invalid-" + control);
        return switch (control) {
            case LEGAL -> new VerifiedControlPayload.Legal("owner-entity", "jurisdiction",
                    candidate.legalScopeSha256(), h,
                    NOW.minus(Duration.ofDays(90)), NOW.plus(Duration.ofDays(20)),
                    VerifiedControlPayload.DeploymentMatch.MISMATCH);
            case REPRODUCIBILITY -> new VerifiedControlPayload.Reproducibility("a".repeat(40),
                    h, "sha256:" + hash("image"), h, h, 42L, "model-v1", h,
                    candidate.agentBundleSha256(),
                    VerifiedControlPayload.Result.FAIL, VerifiedControlPayload.Result.PASS,
                    VerifiedControlPayload.Result.PASS);
            case RESEARCH_ALPHA -> new VerifiedControlPayload.ResearchAlpha(h, h, h, h,
                    BigDecimal.ZERO, 30, 8,
                    VerifiedControlPayload.Result.PASS, VerifiedControlPayload.Result.PASS,
                    VerifiedControlPayload.Result.PASS, VerifiedControlPayload.Result.PASS,
                    VerifiedControlPayload.Result.PASS);
            case EXECUTION_CONSISTENCY ->
                    new VerifiedControlPayload.ExecutionConsistency(
                            h, 100, 100, 100, 1, 0, 0);
            case RISK_CONTROLS -> new VerifiedControlPayload.RiskControls(
                    candidate.policyArtifactSha256(), h,
                    VerifiedControlPayload.Result.PASS, VerifiedControlPayload.Result.UNKNOWN,
                    VerifiedControlPayload.Result.PASS, VerifiedControlPayload.Result.PASS,
                    VerifiedControlPayload.Result.PASS);
            case RECONCILIATION -> new VerifiedControlPayload.Reconciliation(
                    h, 100, 0, 0, 0, 1, 0);
            case SECURITY -> new VerifiedControlPayload.Security(h, h, h, h,
                    VerifiedControlPayload.Result.PASS, VerifiedControlPayload.Result.PASS,
                    VerifiedControlPayload.Result.PASS, VerifiedControlPayload.Result.PASS,
                    100, 0, 1);
            case OPERATIONS -> new VerifiedControlPayload.Operations(h, h, h,
                    VerifiedControlPayload.Result.PASS, VerifiedControlPayload.Result.PASS,
                    VerifiedControlPayload.Result.PASS,
                    Duration.ofDays(6), 20, 0);
            case AGENT_ASSURANCE -> new VerifiedControlPayload.AgentAssurance(h, h, h, h,
                    candidate.agentBundleSha256(),
                    level == AutonomyLevel.L3_ADVISORY
                            ? VerifiedControlPayload.ApprovalMode.PER_RECOMMENDATION
                            : VerifiedControlPayload.ApprovalMode.PREAPPROVED_ENVELOPE,
                    VerifiedControlPayload.GatewayAccess.DIRECT,
                    VerifiedControlPayload.Result.PASS, VerifiedControlPayload.Result.PASS,
                    1000, 0, 0);
            case CAPITAL_CONTROLS -> new VerifiedControlPayload.CapitalControls(
                    candidate.accountScope(), candidate.policyArtifactSha256(),
                    VerifiedControlPayload.AccountIsolation.SHARED,
                    VerifiedControlPayload.WithdrawalPermission.PROHIBITED,
                    candidate.envelope().valuationCurrency(),
                    candidate.envelope().maxGrossNotional(), candidate.envelope().maxDailyLoss(),
                    candidate.envelope().capabilityTtl());
            case OWNER_VENUE_CONTRACT -> new VerifiedControlPayload.OwnerVenueContract(
                    candidate.envelope().venue(), candidate.accountScope(), h, 50, 1);
            case CROSS_MARKET_STATE -> new VerifiedControlPayload.CrossMarketState(h,
                    java.util.Map.of(VerifiedControlPayload.MarketRegime.TRENDING, 100L),
                    NOW.minus(Duration.ofDays(120)), NOW.minus(Duration.ofDays(2)), 100, 0);
        };
    }

    private List<VerifiedControlPayload> invalidPayloadVariants(
            LongTermControl control,
            AutonomyLevel level,
            LongTermReadinessSubject candidate) {
        String h = hash("invalid-variant-" + control);
        List<VerifiedControlPayload> variants = new ArrayList<>();
        variants.add(invalidPayload(control, level, candidate));
        switch (control) {
            case LEGAL -> variants.addAll(List.of(
                    new VerifiedControlPayload.Legal("entity", "jurisdiction",
                            hash("wrong-legal-scope"), h,
                            NOW.minus(Duration.ofDays(90)), NOW.plus(Duration.ofDays(20)),
                            VerifiedControlPayload.DeploymentMatch.MATCH),
                    new VerifiedControlPayload.Legal("entity", "jurisdiction",
                            candidate.legalScopeSha256(), h,
                            NOW.minus(Duration.ofDays(90)), NOW.plus(Duration.ofDays(1)),
                            VerifiedControlPayload.DeploymentMatch.MATCH),
                    new VerifiedControlPayload.Legal("entity", "jurisdiction",
                            candidate.legalScopeSha256(), h,
                            NOW, NOW.plus(Duration.ofDays(20)),
                            VerifiedControlPayload.DeploymentMatch.MATCH)));
            case REPRODUCIBILITY -> variants.addAll(List.of(
                    reproducibility(h, hash("wrong-agent-bundle"),
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS),
                    reproducibility(h, candidate.agentBundleSha256(),
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.FAIL,
                            VerifiedControlPayload.Result.PASS),
                    reproducibility(h, candidate.agentBundleSha256(),
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.UNKNOWN)));
            case RESEARCH_ALPHA -> variants.addAll(List.of(
                    research(h, BigDecimal.ONE, 0, 0,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS),
                    research(h, BigDecimal.ONE, 10, 11,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS),
                    research(h, BigDecimal.ONE, 10, 1,
                            VerifiedControlPayload.Result.FAIL,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS),
                    research(h, BigDecimal.ONE, 10, 1,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.FAIL,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS),
                    research(h, BigDecimal.ONE, 10, 1,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.FAIL,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS),
                    research(h, BigDecimal.ONE, 10, 1,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.FAIL,
                            VerifiedControlPayload.Result.PASS),
                    research(h, BigDecimal.ONE, 10, 1,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.FAIL)));
            case EXECUTION_CONSISTENCY -> variants.addAll(List.of(
                    consistency(h, 0, 100, 100, 0, 0, 0),
                    consistency(h, 100, 0, 100, 0, 0, 0),
                    consistency(h, 100, 100, 0, 0, 0, 0),
                    consistency(h, 100, 100, 100, 0, 1, 0),
                    consistency(h, 100, 100, 100, 0, 0, 1)));
            case RISK_CONTROLS -> variants.addAll(List.of(
                    risk(hash("wrong-policy"), h, VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS),
                    risk(candidate.policyArtifactSha256(), h,
                            VerifiedControlPayload.Result.FAIL,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS),
                    risk(candidate.policyArtifactSha256(), h,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.FAIL,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS),
                    risk(candidate.policyArtifactSha256(), h,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.FAIL,
                            VerifiedControlPayload.Result.PASS),
                    risk(candidate.policyArtifactSha256(), h,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.FAIL)));
            case RECONCILIATION -> variants.addAll(List.of(
                    reconciliation(h, 0, 0, 0, 0, 0, 0),
                    reconciliation(h, 100, 1, 0, 0, 0, 0),
                    reconciliation(h, 100, 0, 1, 0, 0, 0),
                    reconciliation(h, 100, 0, 0, 1, 0, 0),
                    reconciliation(h, 100, 0, 0, 0, 0, 1)));
            case SECURITY -> variants.addAll(List.of(
                    security(h, VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS, 100, 1, 0),
                    security(h, VerifiedControlPayload.Result.FAIL,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS, 100, 0, 0),
                    security(h, VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.FAIL,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS, 100, 0, 0),
                    security(h, VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.FAIL,
                            VerifiedControlPayload.Result.PASS, 100, 0, 0),
                    security(h, VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.FAIL, 100, 0, 0),
                    security(h, VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS, 0, 0, 0)));
            case OPERATIONS -> variants.addAll(List.of(
                    operations(h, VerifiedControlPayload.Result.FAIL,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS, Duration.ofDays(7), 1, 0),
                    operations(h, VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.FAIL,
                            VerifiedControlPayload.Result.PASS, Duration.ofDays(7), 1, 0),
                    operations(h, VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.FAIL, Duration.ofDays(7), 1, 0),
                    operations(h, VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS, Duration.ofDays(7), 0, 0),
                    operations(h, VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS, Duration.ofDays(7), 1, 1)));
            case AGENT_ASSURANCE -> variants.addAll(List.of(
                    agent(h, hash("wrong-agent-bundle"),
                            VerifiedControlPayload.ApprovalMode.PREAPPROVED_ENVELOPE,
                            VerifiedControlPayload.GatewayAccess.NONE,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS, 100, 0, 0),
                    agent(h, candidate.agentBundleSha256(),
                            VerifiedControlPayload.ApprovalMode.PER_RECOMMENDATION,
                            VerifiedControlPayload.GatewayAccess.NONE,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS, 100, 0, 0),
                    agent(h, candidate.agentBundleSha256(),
                            VerifiedControlPayload.ApprovalMode.PREAPPROVED_ENVELOPE,
                            VerifiedControlPayload.GatewayAccess.NONE,
                            VerifiedControlPayload.Result.FAIL,
                            VerifiedControlPayload.Result.PASS, 100, 0, 0),
                    agent(h, candidate.agentBundleSha256(),
                            VerifiedControlPayload.ApprovalMode.PREAPPROVED_ENVELOPE,
                            VerifiedControlPayload.GatewayAccess.NONE,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.FAIL, 100, 0, 0),
                    agent(h, candidate.agentBundleSha256(),
                            VerifiedControlPayload.ApprovalMode.PREAPPROVED_ENVELOPE,
                            VerifiedControlPayload.GatewayAccess.NONE,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS, 0, 0, 0),
                    agent(h, candidate.agentBundleSha256(),
                            VerifiedControlPayload.ApprovalMode.PREAPPROVED_ENVELOPE,
                            VerifiedControlPayload.GatewayAccess.NONE,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS, 100, 1, 0),
                    agent(h, candidate.agentBundleSha256(),
                            VerifiedControlPayload.ApprovalMode.PREAPPROVED_ENVELOPE,
                            VerifiedControlPayload.GatewayAccess.NONE,
                            VerifiedControlPayload.Result.PASS,
                            VerifiedControlPayload.Result.PASS, 100, 0, 1)));
            case CAPITAL_CONTROLS -> variants.addAll(List.of(
                    capital(candidate, "other-account", candidate.policyArtifactSha256(),
                            VerifiedControlPayload.AccountIsolation.ISOLATED_SUBACCOUNT,
                            VerifiedControlPayload.WithdrawalPermission.PROHIBITED,
                            candidate.envelope().valuationCurrency(),
                            candidate.envelope().maxGrossNotional(),
                            candidate.envelope().maxDailyLoss(), candidate.envelope().capabilityTtl()),
                    capital(candidate, candidate.accountScope(), hash("wrong-policy"),
                            VerifiedControlPayload.AccountIsolation.ISOLATED_SUBACCOUNT,
                            VerifiedControlPayload.WithdrawalPermission.PROHIBITED,
                            candidate.envelope().valuationCurrency(),
                            candidate.envelope().maxGrossNotional(),
                            candidate.envelope().maxDailyLoss(), candidate.envelope().capabilityTtl()),
                    capital(candidate, candidate.accountScope(), candidate.policyArtifactSha256(),
                            VerifiedControlPayload.AccountIsolation.ISOLATED_SUBACCOUNT,
                            VerifiedControlPayload.WithdrawalPermission.ALLOWED,
                            candidate.envelope().valuationCurrency(),
                            candidate.envelope().maxGrossNotional(),
                            candidate.envelope().maxDailyLoss(), candidate.envelope().capabilityTtl()),
                    capital(candidate, candidate.accountScope(), candidate.policyArtifactSha256(),
                            VerifiedControlPayload.AccountIsolation.ISOLATED_SUBACCOUNT,
                            VerifiedControlPayload.WithdrawalPermission.PROHIBITED, "USD",
                            candidate.envelope().maxGrossNotional(),
                            candidate.envelope().maxDailyLoss(), candidate.envelope().capabilityTtl()),
                    capital(candidate, candidate.accountScope(), candidate.policyArtifactSha256(),
                            VerifiedControlPayload.AccountIsolation.ISOLATED_SUBACCOUNT,
                            VerifiedControlPayload.WithdrawalPermission.PROHIBITED,
                            candidate.envelope().valuationCurrency(),
                            candidate.envelope().maxGrossNotional().add(BigDecimal.ONE),
                            candidate.envelope().maxDailyLoss(), candidate.envelope().capabilityTtl()),
                    capital(candidate, candidate.accountScope(), candidate.policyArtifactSha256(),
                            VerifiedControlPayload.AccountIsolation.ISOLATED_SUBACCOUNT,
                            VerifiedControlPayload.WithdrawalPermission.PROHIBITED,
                            candidate.envelope().valuationCurrency(),
                            candidate.envelope().maxGrossNotional(),
                            candidate.envelope().maxDailyLoss().add(BigDecimal.ONE),
                            candidate.envelope().capabilityTtl()),
                    capital(candidate, candidate.accountScope(), candidate.policyArtifactSha256(),
                            VerifiedControlPayload.AccountIsolation.ISOLATED_SUBACCOUNT,
                            VerifiedControlPayload.WithdrawalPermission.PROHIBITED,
                            candidate.envelope().valuationCurrency(),
                            candidate.envelope().maxGrossNotional(),
                            candidate.envelope().maxDailyLoss(), Duration.ofMinutes(6))));
            case OWNER_VENUE_CONTRACT -> variants.addAll(List.of(
                    new VerifiedControlPayload.OwnerVenueContract(
                            "OKX", candidate.accountScope(), h, 10, 0),
                    new VerifiedControlPayload.OwnerVenueContract(
                            candidate.envelope().venue(), "other-account", h, 10, 0),
                    new VerifiedControlPayload.OwnerVenueContract(
                            candidate.envelope().venue(), candidate.accountScope(), h, 0, 0)));
            case CROSS_MARKET_STATE -> variants.addAll(List.of(
                    crossMarket(h, java.util.Map.of(
                            VerifiedControlPayload.MarketRegime.TRENDING, 1L,
                            VerifiedControlPayload.MarketRegime.STRESS, 0L),
                            NOW.minus(Duration.ofDays(10)), NOW.minus(Duration.ofDays(1)), 1, 0),
                    crossMarket(h, java.util.Map.of(
                            VerifiedControlPayload.MarketRegime.TRENDING, 1L,
                            VerifiedControlPayload.MarketRegime.STRESS, 1L),
                            NOW.minus(Duration.ofDays(1)), NOW.minus(Duration.ofDays(2)), 1, 0),
                    crossMarket(h, java.util.Map.of(
                            VerifiedControlPayload.MarketRegime.TRENDING, 1L,
                            VerifiedControlPayload.MarketRegime.STRESS, 1L),
                            NOW.minus(Duration.ofDays(10)), NOW, 1, 0),
                    crossMarket(h, java.util.Map.of(
                            VerifiedControlPayload.MarketRegime.TRENDING, 1L,
                            VerifiedControlPayload.MarketRegime.STRESS, 1L),
                            NOW.minus(Duration.ofDays(10)), NOW.minus(Duration.ofDays(1)), 0, 0),
                    crossMarket(h, java.util.Map.of(
                            VerifiedControlPayload.MarketRegime.TRENDING, 1L,
                            VerifiedControlPayload.MarketRegime.STRESS, 1L),
                            NOW.minus(Duration.ofDays(10)), NOW.minus(Duration.ofDays(1)), 1, 1)));
        }
        return List.copyOf(variants);
    }

    private VerifiedControlPayload.Reproducibility reproducibility(
            String hash,
            String agentBundleSha256,
            VerifiedControlPayload.Result cleanRebuild,
            VerifiedControlPayload.Result immutableReplay,
            VerifiedControlPayload.Result deterministicRerun) {
        return new VerifiedControlPayload.Reproducibility("a".repeat(40), hash,
                "sha256:" + hash("variant-image"), hash, hash, 42L, "model-v1", hash,
                agentBundleSha256, cleanRebuild, immutableReplay, deterministicRerun);
    }

    private VerifiedControlPayload.ResearchAlpha research(
            String hash,
            BigDecimal netAlpha,
            int trials,
            int failedTrials,
            VerifiedControlPayload.Result oos,
            VerifiedControlPayload.Result pboDsr,
            VerifiedControlPayload.Result multipleTesting,
            VerifiedControlPayload.Result costCapacity,
            VerifiedControlPayload.Result benchmark) {
        return new VerifiedControlPayload.ResearchAlpha(hash, hash, hash, hash, netAlpha,
                trials, failedTrials, oos, pboDsr, multipleTesting, costCapacity, benchmark);
    }

    private VerifiedControlPayload.ExecutionConsistency consistency(
            String hash, long orders, long fills, long pnlSnapshots,
            long orderDifferences, long fillDifferences, long pnlDifferences) {
        return new VerifiedControlPayload.ExecutionConsistency(hash, orders, fills, pnlSnapshots,
                orderDifferences, fillDifferences, pnlDifferences);
    }

    private VerifiedControlPayload.RiskControls risk(
            String policySha256,
            String reportSha256,
            VerifiedControlPayload.Result killSwitch,
            VerifiedControlPayload.Result fencing,
            VerifiedControlPayload.Result reduceOnly,
            VerifiedControlPayload.Result downgrade,
            VerifiedControlPayload.Result takeover) {
        return new VerifiedControlPayload.RiskControls(policySha256, reportSha256, killSwitch,
                fencing, reduceOnly, downgrade, takeover);
    }

    private VerifiedControlPayload.Reconciliation reconciliation(
            String hash, long cycles, long orders, long fills, long balances,
            long positions, long ledger) {
        return new VerifiedControlPayload.Reconciliation(hash, cycles, orders, fills,
                balances, positions, ledger);
    }

    private VerifiedControlPayload.Security security(
            String hash,
            VerifiedControlPayload.Result custody,
            VerifiedControlPayload.Result mfa,
            VerifiedControlPayload.Result dualApproval,
            VerifiedControlPayload.Result rotation,
            int scannedAssets,
            int critical,
            int high) {
        return new VerifiedControlPayload.Security(hash, hash, hash, hash, custody, mfa,
                dualApproval, rotation, scannedAssets, critical, high);
    }

    private VerifiedControlPayload.Operations operations(
            String hash,
            VerifiedControlPayload.Result alert,
            VerifiedControlPayload.Result restore,
            VerifiedControlPayload.Result rpoRto,
            Duration soak,
            int chaosEvents,
            int incidents) {
        return new VerifiedControlPayload.Operations(hash, hash, hash, alert, restore, rpoRto,
                soak, chaosEvents, incidents);
    }

    private VerifiedControlPayload.AgentAssurance agent(
            String hash,
            String bundleSha256,
            VerifiedControlPayload.ApprovalMode approvalMode,
            VerifiedControlPayload.GatewayAccess gatewayAccess,
            VerifiedControlPayload.Result revocation,
            VerifiedControlPayload.Result incrementalValue,
            int cases,
            int escapes,
            int secrets) {
        return new VerifiedControlPayload.AgentAssurance(hash, hash, hash, hash,
                bundleSha256, approvalMode, gatewayAccess, revocation, incrementalValue,
                cases, escapes, secrets);
    }

    private VerifiedControlPayload.CapitalControls capital(
            LongTermReadinessSubject candidate,
            String account,
            String policySha256,
            VerifiedControlPayload.AccountIsolation isolation,
            VerifiedControlPayload.WithdrawalPermission withdrawal,
            String currency,
            BigDecimal notional,
            BigDecimal dailyLoss,
            Duration ttl) {
        return new VerifiedControlPayload.CapitalControls(account, policySha256, isolation,
                withdrawal, currency, notional, dailyLoss, ttl);
    }

    private VerifiedControlPayload.CrossMarketState crossMarket(
            String hash,
            java.util.Map<VerifiedControlPayload.MarketRegime, Long> observations,
            Instant startedAt,
            Instant endedAt,
            long orders,
            long unexplained) {
        return new VerifiedControlPayload.CrossMarketState(hash, observations, startedAt,
                endedAt, orders, unexplained);
    }

    private static LongTermReadinessSubject subject() {
        return new LongTermReadinessSubject(SUBJECT_SHA, "release-1", RELEASE_SHA,
                "BINANCE:owner-subaccount", "live", "risk-v4",
                POLICY_SHA, AGENT_BUNDLE_SHA, LEGAL_SCOPE_SHA,
                new AutonomyEnvelope("binance", "perpetual", Set.of("BTCUSDT", "ETHUSDT"),
                        NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(30)),
                        "USDT", new BigDecimal("1000"), new BigDecimal("50"),
                        Duration.ofMinutes(1)));
    }

    private static List<ExternalEvidenceReference> references(AutonomyLevel level) {
        List<ExternalEvidenceReference> references = new ArrayList<>();
        for (LongTermControl control : LongTermControl.values()) {
            references.add(reference(level + "-" + control, control, level,
                    hash(level + "-content-" + control)));
        }
        return List.copyOf(references);
    }

    private static ExternalEvidenceReference reference(String id,
                                                       LongTermControl control,
                                                       AutonomyLevel level,
                                                       String contentSha256) {
        return new ExternalEvidenceReference(id, control,
                URI.create("evidence://store/" + level + "/" + control), contentSha256);
    }

    private static String receipt(ExternalEvidenceReference reference) {
        return "receipt-" + reference.evidenceId();
    }

    private static String schema(LongTermControl control) {
        return "crypto-trading/long-term-readiness/"
                + control.name().toLowerCase(java.util.Locale.ROOT) + "/v1";
    }

    private static Binding binding(LongTermReadinessSubject subject) {
        return new Binding(subject.subjectSha256(), subject.sha256(), subject.releaseId(),
                subject.releaseArtifactSha256(), subject.accountScope(), subject.environment(),
                subject.policyVersion(), subject.policyArtifactSha256(),
                subject.agentBundleSha256(), subject.legalScopeSha256(),
                subject.envelope().sha256());
    }

    private static Binding mismatchedBinding(LongTermReadinessSubject subject,
                                             BindingField field) {
        Binding valid = binding(subject);
        return new Binding(
                field == BindingField.SUBJECT ? hash("other-subject") : valid.subjectSha256(),
                field == BindingField.READINESS_SUBJECT ? hash("other-candidate")
                        : valid.readinessSubjectSha256(),
                field == BindingField.RELEASE_ID ? "release-other" : valid.releaseId(),
                field == BindingField.RELEASE_ARTIFACT ? hash("other-release")
                        : valid.releaseArtifactSha256(),
                field == BindingField.ACCOUNT ? "other-account" : valid.accountScope(),
                field == BindingField.ENVIRONMENT ? "PAPER" : valid.environment(),
                field == BindingField.POLICY ? "other-policy" : valid.policyVersion(),
                field == BindingField.POLICY_ARTIFACT ? hash("other-policy-artifact")
                        : valid.policyArtifactSha256(),
                field == BindingField.AGENT_BUNDLE ? hash("other-agent-bundle")
                        : valid.agentBundleSha256(),
                field == BindingField.LEGAL_SCOPE ? hash("other-legal-scope")
                        : valid.legalScopeSha256(),
                field == BindingField.ENVELOPE ? hash("other-envelope")
                        : valid.envelopeSha256());
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private enum BindingField {
        SUBJECT,
        READINESS_SUBJECT,
        RELEASE_ID,
        RELEASE_ARTIFACT,
        ACCOUNT,
        ENVIRONMENT,
        POLICY,
        POLICY_ARTIFACT,
        AGENT_BUNDLE,
        LEGAL_SCOPE,
        ENVELOPE
    }

    private enum CommonClaimField {
        EVIDENCE_ID,
        SCHEMA_VERSION,
        AUDIENCE,
        ARTIFACT_URI,
        CONTENT_SHA256,
        PAYLOAD_CONTROL
    }

    private record Binding(String subjectSha256,
                           String readinessSubjectSha256,
                           String releaseId,
                           String releaseArtifactSha256,
                           String accountScope,
                           String environment,
                           String policyVersion,
                           String policyArtifactSha256,
                           String agentBundleSha256,
                           String legalScopeSha256,
                           String envelopeSha256) {
    }

    @FunctionalInterface
    private interface VerifierOverride {
        EvidenceVerification verify(ExternalEvidenceReference reference,
                                    LongTermReadinessSubject candidate,
                                    AutonomyLevel level);
    }
}
