package com.tj.crypto.autonomy.readiness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Fail-closed, read-only promotion gate for L3 advisory and L4 bounded autonomy.
 *
 * <p>The gate neither invokes an Agent nor mutates lifecycle, live trading, OMS or venue state.
 * A GO result is only readiness evidence and is deliberately not executable authority.</p>
 */
public final class LongTermReadinessGate {
    private static final Set<LongTermControl> REQUIRED =
            EnumSet.allOf(LongTermControl.class);
    private static final String AUDIENCE = "crypto-trading-long-term-readiness-v1";
    private static final String UNBOUND_SHA256 = "0".repeat(64);
    private static final Duration MINIMUM_OPERATIONAL_SOAK = Duration.ofDays(7);
    private static final Duration MAX_OBSERVED_AGE = Duration.ofDays(30);
    private static final Duration MAX_EVIDENCE_TTL = Duration.ofDays(30);
    private static final Duration MAX_VERIFICATION_AGE = Duration.ofHours(24);
    private static final Duration MAX_ENVELOPE_HORIZON = Duration.ofDays(90);
    private static final Duration MAX_CAPABILITY_TTL = Duration.ofMinutes(2);

    private final ReadinessEvidenceSource evidenceSource;
    private final ExternalReadinessEvidenceVerifier externalVerifier;

    /** Empty evidence plus an unconfigured trust root always yields NO_GO. */
    public LongTermReadinessGate() {
        this(ReadinessEvidenceSource.empty(), ExternalReadinessEvidenceVerifier.rejecting());
    }

    /** Compatibility constructor deliberately uses the permanently rejecting verifier. */
    public LongTermReadinessGate(ReadinessEvidenceSource evidenceSource) {
        this(evidenceSource, ExternalReadinessEvidenceVerifier.rejecting());
    }

    public LongTermReadinessGate(ReadinessEvidenceSource evidenceSource,
                                 ExternalReadinessEvidenceVerifier externalVerifier) {
        this.evidenceSource = Objects.requireNonNull(evidenceSource, "evidenceSource");
        this.externalVerifier = Objects.requireNonNull(externalVerifier, "externalVerifier");
    }

    public LongTermReadinessDecision evaluateL3(LongTermReadinessSubject subject, Instant now) {
        return evaluate(subject, AutonomyLevel.L3_ADVISORY, now);
    }

    public LongTermReadinessDecision evaluateL4(LongTermReadinessSubject subject, Instant now) {
        return evaluate(subject, AutonomyLevel.L4_BOUNDED_AUTONOMY, now);
    }

    public LongTermReadinessDecision evaluateL3(LongTermReadinessSubject subject, Clock clock) {
        return evaluateL3(subject, Objects.requireNonNull(clock, "clock").instant());
    }

    public LongTermReadinessDecision evaluateL4(LongTermReadinessSubject subject, Clock clock) {
        return evaluateL4(subject, Objects.requireNonNull(clock, "clock").instant());
    }

    public LongTermReadinessAssessment evaluateBoth(LongTermReadinessSubject subject, Instant now) {
        LongTermReadinessDecision l3 = evaluateLevel(
                subject, AutonomyLevel.L3_ADVISORY, now);
        LongTermReadinessDecision l4 = evaluateLevel(
                subject, AutonomyLevel.L4_BOUNDED_AUTONOMY, now);
        return LongTermReadinessAssessment.create(
                l3, requireL3Prerequisite(subject, now, l3, l4));
    }

    public LongTermReadinessDecision evaluate(LongTermReadinessSubject subject,
                                              AutonomyLevel level,
                                              Instant now) {
        if (level != AutonomyLevel.L4_BOUNDED_AUTONOMY) {
            return evaluateLevel(subject, level, now);
        }
        LongTermReadinessDecision l3 = evaluateLevel(
                subject, AutonomyLevel.L3_ADVISORY, now);
        LongTermReadinessDecision l4 = evaluateLevel(subject, level, now);
        return requireL3Prerequisite(subject, now, l3, l4);
    }

    private LongTermReadinessDecision evaluateLevel(LongTermReadinessSubject subject,
                                                    AutonomyLevel level,
                                                    Instant now) {
        AutonomyLevel effectiveLevel = level == null
                ? AutonomyLevel.L4_BOUNDED_AUTONOMY : level;
        Instant effectiveNow = now == null ? Instant.EPOCH : now;
        if (subject == null || level == null || now == null) {
            List<String> blockers = new ArrayList<>();
            if (subject == null) blockers.add("READINESS_SUBJECT: exact subject is required");
            if (level == null) blockers.add("AUTONOMY_LEVEL: exact target is required");
            if (now == null) blockers.add("DECISION_TIME: exact UTC instant is required");
            return deniedUnbound(effectiveLevel, effectiveNow, blockers);
        }

        LinkedHashSet<String> blockers = new LinkedHashSet<>();
        if (!subject.envelope().isActiveAt(now)) {
            blockers.add("AUTONOMY_ENVELOPE: inactive or expired");
            return deniedBound(subject, level, now, List.copyOf(blockers));
        }
        if (exceeds(now, subject.envelope().validUntil(), MAX_ENVELOPE_HORIZON)) {
            blockers.add("AUTONOMY_ENVELOPE: validity horizon exceeds hard policy");
            return deniedBound(subject, level, now, List.copyOf(blockers));
        }
        if (subject.envelope().capabilityTtl().compareTo(MAX_CAPABILITY_TTL) > 0) {
            blockers.add("AUTONOMY_ENVELOPE: capability TTL exceeds hard policy");
            return deniedBound(subject, level, now, List.copyOf(blockers));
        }

        List<ExternalEvidenceReference> references;
        try {
            references = evidenceSource.findAll(subject, level);
            if (references == null) {
                blockers.add("EVIDENCE_SOURCE: returned no result");
                references = List.of();
            }
        } catch (RuntimeException queryFailure) {
            blockers.add("EVIDENCE_SOURCE: query failed");
            references = List.of();
        }

        EnumMap<LongTermControl, List<ExternalEvidenceReference>> byControl =
                new EnumMap<>(LongTermControl.class);
        TreeMap<String, List<ExternalEvidenceReference>> byId = new TreeMap<>();
        TreeMap<String, List<ExternalEvidenceReference>> byDigest = new TreeMap<>();
        TreeMap<String, List<ExternalEvidenceReference>> byArtifactUri = new TreeMap<>();
        for (ExternalEvidenceReference reference : references) {
            if (reference == null) {
                blockers.add("EVIDENCE_REFERENCE: null reference");
                continue;
            }
            byControl.computeIfAbsent(reference.control(), ignored -> new ArrayList<>())
                    .add(reference);
            byId.computeIfAbsent(reference.evidenceId(), ignored -> new ArrayList<>())
                    .add(reference);
            byDigest.computeIfAbsent(reference.declaredContentSha256(), ignored -> new ArrayList<>())
                    .add(reference);
            byArtifactUri.computeIfAbsent(reference.artifactUri().toASCIIString(),
                    ignored -> new ArrayList<>()).add(reference);
        }

        EnumSet<LongTermControl> conflicts = EnumSet.noneOf(LongTermControl.class);
        REQUIRED.forEach(control -> {
            List<ExternalEvidenceReference> candidates = byControl.getOrDefault(
                    control, List.of());
            if (candidates.isEmpty()) {
                blockers.add(control + ": NOT_VERIFIED");
            } else if (candidates.size() != 1) {
                blockers.add(control + ": duplicate control evidence");
                conflicts.add(control);
            }
        });
        markReferenceDuplicates(byId, "duplicate evidence ID", conflicts, blockers);
        markReferenceDuplicates(byDigest, "duplicate evidence content", conflicts, blockers);
        markReferenceDuplicates(byArtifactUri, "duplicate evidence artifact URI", conflicts, blockers);

        EnumMap<LongTermControl, VerifiedReadinessClaims> candidates =
                new EnumMap<>(LongTermControl.class);
        for (LongTermControl control : REQUIRED) {
            List<ExternalEvidenceReference> matching = byControl.get(control);
            if (matching == null || matching.size() != 1 || conflicts.contains(control)) continue;
            ExternalEvidenceReference reference = matching.get(0);
            EvidenceVerification verification;
            try {
                verification = externalVerifier.verify(reference, subject, level, now);
            } catch (RuntimeException verifierFailure) {
                blockers.add(control + ": external verification failed");
                continue;
            }
            if (!(verification instanceof VerifiedReadinessClaims claims)) {
                blockers.add(control + ": external verifier returned NOT_VERIFIED");
                continue;
            }
            List<String> claimBlockers = validateClaims(reference, claims, subject, level, now);
            if (!claimBlockers.isEmpty()) {
                claimBlockers.forEach(reason -> blockers.add(control + ": " + reason));
                continue;
            }
            candidates.put(control, claims);
        }

        EnumSet<LongTermControl> verificationConflicts = EnumSet.noneOf(LongTermControl.class);
        markClaimDuplicates(candidates, VerifiedReadinessClaims::verifierReceiptId,
                "duplicate verifier receipt", verificationConflicts, blockers);
        markClaimDuplicates(candidates, VerifiedReadinessClaims::contentSha256,
                "duplicate verified content", verificationConflicts, blockers);

        EnumMap<LongTermControl, AcceptedEvidence> accepted =
                new EnumMap<>(LongTermControl.class);
        Instant decisionExpiry = subject.envelope().validUntil();
        for (Map.Entry<LongTermControl, VerifiedReadinessClaims> entry : candidates.entrySet()) {
            if (verificationConflicts.contains(entry.getKey())) continue;
            VerifiedReadinessClaims claims = entry.getValue();
            accepted.put(entry.getKey(), new AcceptedEvidence(claims.evidenceId(),
                    claims.contentSha256(), claims.artifactUri(), claims.schemaVersion(),
                    claims.verifierReceiptId(), claims.verifierId(),
                    claims.verifiedIssuer(), claims.signatureKeyId(), claims.signatureAlgorithm(),
                    claims.verifiedAt(),
                    claims.observedAt(), claims.expiresAt()));
            Instant claimExpiry = earliest(claims.expiresAt(),
                    safePlus(claims.verifiedAt(), MAX_VERIFICATION_AGE, now),
                    safePlus(claims.observedAt(), MAX_OBSERVED_AGE, now));
            if (claimExpiry.isBefore(decisionExpiry)) decisionExpiry = claimExpiry;
        }
        if (accepted.size() != REQUIRED.size() && blockers.isEmpty()) {
            blockers.add("EVIDENCE_SET: incomplete");
        }

        List<String> immutableBlockers = List.copyOf(blockers);
        String evidenceSetSha256 = evidenceSetSha256(
                subject.sha256(), level, accepted, immutableBlockers);
        ReadinessVerdict verdict = immutableBlockers.isEmpty()
                && accepted.size() == REQUIRED.size()
                ? ReadinessVerdict.GO : ReadinessVerdict.NO_GO;
        return LongTermReadinessDecision.create(level, verdict, subject.sha256(), now,
                verdict == ReadinessVerdict.GO ? decisionExpiry : now,
                evidenceSetSha256, UNBOUND_SHA256, accepted, immutableBlockers);
    }

    private void markReferenceDuplicates(
            Map<String, List<ExternalEvidenceReference>> grouped,
            String reason,
            Set<LongTermControl> conflicts,
            Set<String> blockers) {
        grouped.forEach((value, references) -> {
            if (references.size() > 1) {
                references.forEach(reference -> conflicts.add(reference.control()));
                blockers.add("EVIDENCE_SET: " + reason);
            }
        });
    }

    private void markClaimDuplicates(
            Map<LongTermControl, VerifiedReadinessClaims> claims,
            Function<VerifiedReadinessClaims, String> value,
            String reason,
            Set<LongTermControl> conflicts,
            Set<String> blockers) {
        Map<String, List<LongTermControl>> grouped = new HashMap<>();
        claims.forEach((control, item) -> grouped
                .computeIfAbsent(value.apply(item), ignored -> new ArrayList<>()).add(control));
        grouped.values().stream().filter(controls -> controls.size() > 1).forEach(controls -> {
            conflicts.addAll(controls);
            blockers.add("EVIDENCE_SET: " + reason);
        });
    }

    private List<String> validateClaims(ExternalEvidenceReference reference,
                                        VerifiedReadinessClaims claims,
                                        LongTermReadinessSubject subject,
                                        AutonomyLevel level,
                                        Instant now) {
        List<String> blockers = new ArrayList<>();
        String expectedSchema = "crypto-trading/long-term-readiness/"
                + reference.control().name().toLowerCase(Locale.ROOT) + "/v1";
        equal(reference.evidenceId(), claims.evidenceId(), "evidenceId", blockers);
        equal(level, claims.autonomyLevel(), "autonomyLevel", blockers);
        equal(expectedSchema, claims.schemaVersion(), "schemaVersion", blockers);
        equal(AUDIENCE, claims.audience(), "audience", blockers);
        equal(subject.subjectSha256(), claims.subjectSha256(), "subjectSha256", blockers);
        equal(subject.sha256(), claims.readinessSubjectSha256(),
                "readinessSubjectSha256", blockers);
        equal(subject.releaseId(), claims.releaseId(), "releaseId", blockers);
        equal(subject.releaseArtifactSha256(), claims.releaseArtifactSha256(),
                "releaseArtifactSha256", blockers);
        equal(subject.accountScope(), claims.accountScope(), "accountScope", blockers);
        equal(subject.environment(), claims.environment(), "environment", blockers);
        equal(subject.policyVersion(), claims.policyVersion(), "policyVersion", blockers);
        equal(subject.policyArtifactSha256(), claims.policyArtifactSha256(),
                "policyArtifactSha256", blockers);
        equal(subject.agentBundleSha256(), claims.agentBundleSha256(),
                "agentBundleSha256", blockers);
        equal(subject.legalScopeSha256(), claims.legalScopeSha256(),
                "legalScopeSha256", blockers);
        equal(subject.envelope().sha256(), claims.envelopeSha256(),
                "envelopeSha256", blockers);
        equal(reference.artifactUri(), claims.artifactUri(), "artifactUri", blockers);
        equal(reference.declaredContentSha256(), claims.contentSha256(),
                "recomputed content SHA-256", blockers);
        equal(reference.control(), claims.control(), "control payload type", blockers);
        if (claims.outcome() != VerificationOutcome.PASS) {
            blockers.add("outcome is " + claims.outcome());
        }
        if (claims.observedAt().isAfter(now)) blockers.add("observedAt is in the future");
        if (claims.verifiedAt().isAfter(now)) blockers.add("verifiedAt is in the future");
        if (!claims.expiresAt().isAfter(now)) blockers.add("evidence is expired");
        if (!safePlus(claims.observedAt(), MAX_OBSERVED_AGE, now).isAfter(now)) {
            blockers.add("observed evidence is older than hard policy");
        }
        if (exceeds(now, claims.expiresAt(), MAX_EVIDENCE_TTL)) {
            blockers.add("evidence TTL exceeds hard policy");
        }
        if (exceeds(claims.observedAt(), claims.expiresAt(), MAX_EVIDENCE_TTL)
                || exceeds(claims.verifiedAt(), claims.expiresAt(), MAX_EVIDENCE_TTL)) {
            blockers.add("signed evidence validity interval exceeds hard policy");
        }
        if (!safePlus(claims.verifiedAt(), MAX_VERIFICATION_AGE, now).isAfter(now)) {
            blockers.add("external verification is stale");
        }
        blockers.addAll(validatePayload(claims, subject, level, now));
        return blockers;
    }

    private List<String> validatePayload(VerifiedReadinessClaims claims,
                                         LongTermReadinessSubject subject,
                                         AutonomyLevel level,
                                         Instant now) {
        VerifiedControlPayload payload = claims.payload();
        List<String> blockers = new ArrayList<>();
        if (payload instanceof VerifiedControlPayload.Legal legal) {
            if (!legal.legalScopeSha256().equals(subject.legalScopeSha256())) {
                blockers.add("legal opinion scope conflicts with the readiness subject");
            }
            if (!legal.opinionExpiresAt().isAfter(now)
                    || legal.opinionExpiresAt().isBefore(claims.expiresAt())) {
                blockers.add("written legal opinion is expired or shorter than evidence TTL");
            }
            if (legal.opinionIssuedAt().isAfter(claims.observedAt())) {
                blockers.add("legal opinion postdates observed evidence");
            }
            if (legal.actualDeploymentMatch() != VerifiedControlPayload.DeploymentMatch.MATCH) {
                blockers.add("actual deployment is outside the written legal opinion");
            }
        } else if (payload instanceof VerifiedControlPayload.Reproducibility reproducibility) {
            if (!reproducibility.agentBundleSha256().equals(subject.agentBundleSha256())) {
                blockers.add("reproduced Agent bundle conflicts with the readiness subject");
            }
            if (!allPass(reproducibility.cleanRebuild(), reproducibility.immutableDataReplay(),
                    reproducibility.deterministicRerun())) {
                blockers.add("build/data/experiment reproduction is not PASS");
            }
        } else if (payload instanceof VerifiedControlPayload.ResearchAlpha research) {
            if (!allPass(research.outOfSample(), research.pboDsr(),
                    research.multipleTesting(), research.costCapacity(),
                    research.simpleBenchmark())) {
                blockers.add("research policy checks are not all PASS");
            }
            if (research.netOutOfSampleAlpha().compareTo(BigDecimal.ZERO) <= 0) {
                blockers.add("net out-of-sample alpha is not positive");
            }
            if (research.registeredTrials() <= 0
                    || research.failedTrialsRecorded() > research.registeredTrials()) {
                blockers.add("trial registry facts are incomplete");
            }
        } else if (payload instanceof VerifiedControlPayload.ExecutionConsistency parity) {
            if (parity.observedOrders() <= 0 || parity.observedFills() <= 0
                    || parity.observedPnlSnapshots() <= 0) {
                blockers.add("backtest/paper/shadow comparison has no observations");
            }
            if (parity.unexplainedOrderDifferences() != 0
                    || parity.unexplainedFillDifferences() != 0
                    || parity.unexplainedPnlDifferences() != 0) {
                blockers.add("backtest/paper/shadow differences remain unexplained");
            }
        } else if (payload instanceof VerifiedControlPayload.RiskControls risk) {
            if (!risk.riskPolicySha256().equals(subject.policyArtifactSha256())) {
                blockers.add("risk policy artifact conflicts with the readiness subject");
            }
            if (!allPass(risk.killSwitch(), risk.singleWriterFencing(), risk.reduceOnlyPath(),
                    risk.automaticDowngrade(), risk.humanTakeover())) {
                blockers.add("one or more hard-risk recovery paths are not PASS");
            }
        } else if (payload instanceof VerifiedControlPayload.Reconciliation reconciliation) {
            if (reconciliation.convergenceCycles() <= 0) {
                blockers.add("reconciliation has no convergence cycles");
            }
            if (reconciliation.unresolvedOrderDifferences() != 0
                    || reconciliation.unresolvedFillDifferences() != 0
                    || reconciliation.unresolvedBalanceDifferences() != 0
                    || reconciliation.unresolvedPositionDifferences() != 0
                    || reconciliation.unresolvedLedgerDifferences() != 0) {
                blockers.add("reconciliation contains unresolved differences");
            }
        } else if (payload instanceof VerifiedControlPayload.Security security) {
            if (security.scannedAssets() <= 0 || !allPass(security.keyCustody(), security.mfa(),
                    security.dualApproval(), security.rotationRevocation())
                    || security.criticalFindings() != 0 || security.highFindings() != 0) {
                blockers.add("security report contains critical or high findings");
            }
        } else if (payload instanceof VerifiedControlPayload.Operations operations) {
            if (!allPass(operations.alertDelivery(), operations.backupRestore(), operations.rpoRto())
                    || operations.continuousSoak().compareTo(MINIMUM_OPERATIONAL_SOAK) < 0
                    || operations.chaosEvents() <= 0
                    || operations.unexplainedIncidents() != 0) {
                blockers.add("operations evidence lacks 7d soak/chaos or has incidents");
            }
        } else if (payload instanceof VerifiedControlPayload.AgentAssurance agent) {
            if (!agent.agentBundleSha256().equals(subject.agentBundleSha256())) {
                blockers.add("evaluated Agent bundle conflicts with the readiness subject");
            }
            VerifiedControlPayload.ApprovalMode expected = level == AutonomyLevel.L3_ADVISORY
                    ? VerifiedControlPayload.ApprovalMode.PER_RECOMMENDATION
                    : VerifiedControlPayload.ApprovalMode.PREAPPROVED_ENVELOPE;
            if (agent.approvalMode() != expected) {
                blockers.add("Agent approval mode is not valid for " + level);
            }
            if (agent.privateGatewayAccess() != VerifiedControlPayload.GatewayAccess.NONE
                    || agent.immediateRevocation() != VerifiedControlPayload.Result.PASS
                    || agent.incrementalValue() != VerifiedControlPayload.Result.PASS
                    || agent.adversarialCases() <= 0
                    || agent.successfulEscapes() != 0 || agent.secretExposures() != 0) {
                blockers.add("Agent assurance boundaries are not satisfied");
            }
        } else if (payload instanceof VerifiedControlPayload.CapitalControls capital) {
            AutonomyEnvelope envelope = subject.envelope();
            if (!capital.capitalPolicySha256().equals(subject.policyArtifactSha256())
                    || !capital.isolatedAccountScope().equals(subject.accountScope())
                    || capital.accountIsolation()
                    != VerifiedControlPayload.AccountIsolation.ISOLATED_SUBACCOUNT
                    || capital.withdrawalPermission()
                    != VerifiedControlPayload.WithdrawalPermission.PROHIBITED
                    || !capital.valuationCurrency().equals(envelope.valuationCurrency())
                    || capital.maxGrossNotional().compareTo(envelope.maxGrossNotional()) != 0
                    || capital.maxDailyLoss().compareTo(envelope.maxDailyLoss()) != 0
                    || !capital.capabilityTtl().equals(envelope.capabilityTtl())) {
                blockers.add("capital evidence conflicts with the immutable envelope");
            }
        } else if (payload instanceof VerifiedControlPayload.OwnerVenueContract venue) {
            if (!venue.venue().equals(subject.envelope().venue())
                    || !venue.ownerAccountScope().equals(subject.accountScope())
                    || venue.passedCases() <= 0 || venue.failedCases() != 0) {
                blockers.add("owner venue contract is missing, scoped elsewhere or failed");
            }
        } else if (payload instanceof VerifiedControlPayload.CrossMarketState marketState) {
            if (marketState.observationsByRegime().size() < 2
                    || marketState.observationsByRegime().values().stream()
                    .anyMatch(observations -> observations <= 0)
                    || !marketState.windowEndedAt().isAfter(marketState.windowStartedAt())
                    || marketState.windowEndedAt().isAfter(claims.observedAt())
                    || marketState.tracedOrders() <= 0
                    || marketState.unexplainedEvents() != 0) {
                blockers.add("cross-market-state evidence is incomplete or inconsistent");
            }
        } else {
            blockers.add("unsupported typed control payload");
        }
        return blockers;
    }

    private static boolean allPass(VerifiedControlPayload.Result... results) {
        for (VerifiedControlPayload.Result result : results) {
            if (result != VerifiedControlPayload.Result.PASS) return false;
        }
        return true;
    }

    private boolean exceeds(Instant start, Instant end, Duration maximum) {
        try {
            // At the exact freshness ceiling the derived decision expiry is already `end`;
            // treating equality as stale keeps the gate on the stable NO_GO path.
            return Duration.between(start, end).compareTo(maximum) >= 0;
        } catch (RuntimeException invalidRange) {
            return true;
        }
    }

    private Instant safePlus(Instant value, Duration duration, Instant fallback) {
        try {
            return value.plus(duration);
        } catch (RuntimeException overflow) {
            return fallback;
        }
    }

    private Instant earliest(Instant first, Instant second, Instant third) {
        Instant earliest = first.isBefore(second) ? first : second;
        return third.isBefore(earliest) ? third : earliest;
    }

    private void equal(Object expected, Object actual, String name, List<String> blockers) {
        if (!Objects.equals(expected, actual)) blockers.add("signed claim mismatch: " + name);
    }

    private String evidenceSetSha256(String subjectSha256,
                                     AutonomyLevel level,
                                     Map<LongTermControl, AcceptedEvidence> accepted,
                                     List<String> blockers) {
        List<String> components = new ArrayList<>();
        components.add(subjectSha256);
        components.add(level.name());
        for (LongTermControl control : LongTermControl.values()) {
            AcceptedEvidence evidence = accepted.get(control);
            if (evidence == null) {
                components.add(control + ":MISSING");
            } else {
                components.add(control.name());
                components.add(evidence.evidenceId());
                components.add(evidence.contentSha256());
                components.add(evidence.artifactUri().toASCIIString());
                components.add(evidence.schemaVersion());
                components.add(evidence.verifierReceiptId());
                components.add(evidence.verifierId());
                components.add(evidence.verifiedIssuer());
                components.add(evidence.signatureKeyId());
                components.add(evidence.signatureAlgorithm().name());
                components.add(evidence.verifiedAt().toString());
                components.add(evidence.observedAt().toString());
                components.add(evidence.expiresAt().toString());
            }
        }
        blockers.stream().sorted().forEach(components::add);
        return ReadinessHashing.sha256("long-term-readiness-evidence-set-v1", components);
    }

    private LongTermReadinessDecision deniedUnbound(AutonomyLevel level,
                                                    Instant now,
                                                    List<String> blockers) {
        String evidenceSet = evidenceSetSha256(
                UNBOUND_SHA256, level, Map.of(), blockers);
        return LongTermReadinessDecision.create(level, ReadinessVerdict.NO_GO,
                UNBOUND_SHA256, now, now, evidenceSet, UNBOUND_SHA256,
                Map.of(), blockers);
    }

    private LongTermReadinessDecision deniedBound(LongTermReadinessSubject subject,
                                                  AutonomyLevel level,
                                                  Instant now,
                                                  List<String> blockers) {
        String evidenceSet = evidenceSetSha256(subject.sha256(), level, Map.of(), blockers);
        return LongTermReadinessDecision.create(level, ReadinessVerdict.NO_GO,
                subject.sha256(), now, now, evidenceSet, UNBOUND_SHA256,
                Map.of(), blockers);
    }

    private LongTermReadinessDecision requireL3Prerequisite(
            LongTermReadinessSubject subject,
            Instant now,
            LongTermReadinessDecision l3,
            LongTermReadinessDecision l4) {
        LinkedHashSet<String> blockers = new LinkedHashSet<>(l4.blockers());
        Instant decisionTime = now == null ? Instant.EPOCH : now;
        if (!l3.readyAt(decisionTime)) {
            blockers.add("L3_ADVISORY: prerequisite decision is NO_GO or expired");
        } else {
            addCrossLevelReuseBlockers(l3, l4, blockers);
        }
        List<String> immutable = List.copyOf(blockers);
        String subjectSha256 = subject == null ? UNBOUND_SHA256 : subject.sha256();
        String prerequisite = ReadinessDecisionHashing.prerequisite(l3);
        List<String> combinedComponents = new ArrayList<>();
        combinedComponents.add(l4.evidenceSetSha256());
        combinedComponents.add(prerequisite);
        immutable.stream().sorted().forEach(combinedComponents::add);
        String evidenceSet = ReadinessHashing.sha256(
                "long-term-readiness-l4-with-l3-v1", combinedComponents);
        boolean go = l3.readyAt(decisionTime) && l4.readyAt(decisionTime)
                && immutable.equals(l4.blockers());
        Instant expiry = go && l3.expiresAt().isBefore(l4.expiresAt())
                ? l3.expiresAt() : l4.expiresAt();
        return LongTermReadinessDecision.create(AutonomyLevel.L4_BOUNDED_AUTONOMY,
                go ? ReadinessVerdict.GO : ReadinessVerdict.NO_GO,
                subjectSha256, decisionTime, go ? expiry : decisionTime,
                evidenceSet, prerequisite, l4.acceptedEvidence(), immutable);
    }

    private void addCrossLevelReuseBlockers(LongTermReadinessDecision l3,
                                            LongTermReadinessDecision l4,
                                            Set<String> blockers) {
        List<AcceptedEvidence> l3Evidence = List.copyOf(l3.acceptedEvidence().values());
        List<AcceptedEvidence> l4Evidence = List.copyOf(l4.acceptedEvidence().values());
        if (overlaps(l3Evidence, l4Evidence, AcceptedEvidence::evidenceId)) {
            blockers.add("EVIDENCE_SET: evidence ID reused across L3/L4");
        }
        if (overlaps(l3Evidence, l4Evidence, AcceptedEvidence::contentSha256)) {
            blockers.add("EVIDENCE_SET: verified content reused across L3/L4");
        }
        if (overlaps(l3Evidence, l4Evidence,
                evidence -> evidence.artifactUri().toASCIIString())) {
            blockers.add("EVIDENCE_SET: artifact URI reused across L3/L4");
        }
        if (overlaps(l3Evidence, l4Evidence, AcceptedEvidence::verifierReceiptId)) {
            blockers.add("EVIDENCE_SET: verifier receipt reused across L3/L4");
        }
    }

    private boolean overlaps(List<AcceptedEvidence> left,
                             List<AcceptedEvidence> right,
                             Function<AcceptedEvidence, String> value) {
        Set<String> leftValues = new java.util.HashSet<>();
        left.forEach(evidence -> leftValues.add(value.apply(evidence)));
        return right.stream().map(value).anyMatch(leftValues::contains);
    }
}
