package com.tj.crypto.autonomy.canary;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic champion/challenger and canary capital decision layer.
 * It has no execution, OMS, venue, credential, or policy-mutation capability.
 */
public final class CanaryDecisionEngine {
    private final CanaryPolicy policy;
    private final CapitalApprovalVerifier approvalVerifier;
    private final CanaryMetricEvidenceVerifier metricEvidenceVerifier;
    private final CanaryRunStateVerifier runStateVerifier;
    private final Clock trustedClock;

    public CanaryDecisionEngine(CanaryPolicy policy,
                                CapitalApprovalVerifier approvalVerifier,
                                CanaryMetricEvidenceVerifier metricEvidenceVerifier,
                                CanaryRunStateVerifier runStateVerifier,
                                Clock trustedClock) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.approvalVerifier = Objects.requireNonNull(approvalVerifier, "approvalVerifier");
        this.metricEvidenceVerifier = Objects.requireNonNull(metricEvidenceVerifier, "metricEvidenceVerifier");
        this.runStateVerifier = Objects.requireNonNull(runStateVerifier, "runStateVerifier");
        this.trustedClock = Objects.requireNonNull(trustedClock, "trustedClock");
    }

    public CanaryDecision evaluate(CanaryDecisionRequest request) {
        Objects.requireNonNull(request, "request");
        Instant now = trustedClock.instant();
        CanaryRunState state = request.runState();
        EnumSet<CanaryReasonCode> reasons = EnumSet.noneOf(CanaryReasonCode.class);

        if (!state.contentAddressMatches()) {
            reasons.add(CanaryReasonCode.RUN_STATE_CONTENT_DIGEST_MISMATCH);
        }
        if (state.observedAt().isAfter(now)) reasons.add(CanaryReasonCode.RUN_STATE_NOT_YET_OBSERVED);
        if (!state.expiresAt().isAfter(now)) reasons.add(CanaryReasonCode.RUN_STATE_EXPIRED);
        if (!durableHeadVerified(state, now)) {
            reasons.add(CanaryReasonCode.RUN_STATE_DURABLE_HEAD_NOT_VERIFIED);
        }
        if (!deploymentScopesAreValid(policy, state, request)) {
            reasons.add(CanaryReasonCode.DEPLOYMENT_SCOPE_CONTENT_DIGEST_MISMATCH);
        }
        if (!deploymentScopesMatch(policy, state, request)) {
            reasons.add(CanaryReasonCode.DEPLOYMENT_SCOPE_MISMATCH);
        }
        if (!reasons.isEmpty()) {
            return decision(CanaryAction.HALT, reasons, request, "", "", now);
        }
        if (state.stage() == CanaryStage.HALTED) {
            reasons.add(CanaryReasonCode.STAGE_ALREADY_HALTED);
            return decision(CanaryAction.HALT, reasons, request, "", "", now);
        }
        if (!policy.contentAddressMatches()) {
            reasons.add(CanaryReasonCode.POLICY_CONTENT_DIGEST_MISMATCH);
            return decision(CanaryAction.HALT, reasons, request, "", "", now);
        }
        if (!validReleaseDigests(state, request)) {
            reasons.add(CanaryReasonCode.RELEASE_DIGEST_INVALID);
        }
        if (!stateBindingsMatch(state, request)) {
            reasons.add(CanaryReasonCode.STATE_RELEASE_BINDING_MISMATCH);
        }
        Optional<CapitalRung> currentRung = state.stage() == CanaryStage.CANARY
                ? policy.rung(state.currentRungId()) : Optional.empty();
        if (state.stage() == CanaryStage.CANARY && currentRung.isEmpty()) {
            reasons.add(CanaryReasonCode.STATE_RUNG_UNKNOWN);
        }
        if (!sameWindow(request.championSnapshot(), request.challengerSnapshot())) {
            reasons.add(CanaryReasonCode.SNAPSHOT_WINDOW_MISMATCH);
        }
        if (!reasons.isEmpty()) {
            return decision(CanaryAction.HALT, reasons, request, "", "", now);
        }

        SnapshotAssessment champion = assessSnapshot(request.championSnapshot(), true, now);
        SnapshotAssessment challenger = assessSnapshot(request.challengerSnapshot(), false, now);
        reasons.addAll(champion.reasons());
        reasons.addAll(challenger.reasons());
        boolean metricEvidenceUnavailable = false;
        if (champion.usable() && !metricEvidenceVerified(request.championSnapshot(), now)) {
            reasons.add(CanaryReasonCode.CHAMPION_METRIC_EVIDENCE_NOT_VERIFIED);
            metricEvidenceUnavailable = true;
        }
        if (challenger.usable() && !metricEvidenceVerified(request.challengerSnapshot(), now)) {
            reasons.add(CanaryReasonCode.CHALLENGER_METRIC_EVIDENCE_NOT_VERIFIED);
            metricEvidenceUnavailable = true;
        }

        Duration window = Duration.between(request.championSnapshot().windowStart(),
                request.championSnapshot().windowEnd());
        boolean insufficientWindow = window.compareTo(policy.minimumEvaluationWindow()) < 0;
        if (insufficientWindow) reasons.add(CanaryReasonCode.EVALUATION_WINDOW_TOO_SHORT);

        boolean integrityFailure = champion.integrityFailure() || challenger.integrityFailure();
        boolean evidenceUnavailable = champion.evidenceUnavailable() || challenger.evidenceUnavailable()
                || insufficientWindow || metricEvidenceUnavailable;
        if (integrityFailure) {
            return decision(CanaryAction.HALT, reasons, request, "", "", now);
        }
        if (champion.usable() && challenger.usable()) {
            evidenceUnavailable |= addSampleAndCoverageReasons(request, reasons);
        }
        if (evidenceUnavailable) {
            return evidenceUnavailableDecision(request, currentRung, reasons, now);
        }

        BigDecimal championReconciliation = metric(request.championSnapshot(),
                CanaryMetric.UNEXPLAINED_RECONCILIATION_EVENTS);
        BigDecimal challengerReconciliation = metric(request.challengerSnapshot(),
                CanaryMetric.UNEXPLAINED_RECONCILIATION_EVENTS);
        BigDecimal maximumReconciliation = BigDecimal.valueOf(policy.maximumUnexplainedReconciliationEvents());
        if (championReconciliation.compareTo(maximumReconciliation) > 0
                || challengerReconciliation.compareTo(maximumReconciliation) > 0) {
            reasons.add(CanaryReasonCode.UNEXPLAINED_RECONCILIATION_EVENTS);
            return decision(CanaryAction.HALT, reasons, request, "", "", now);
        }

        addPerformanceReasons(request, reasons);
        if (hasPerformanceRegression(reasons)) {
            return performanceRegressionDecision(request, currentRung, reasons, now);
        }

        CapitalRung target;
        CanaryAction successAction;
        CanaryReasonCode successReason;
        if (state.stage() == CanaryStage.SHADOW) {
            target = policy.firstRung();
            successAction = CanaryAction.ENTER_TINY_CANARY;
            successReason = CanaryReasonCode.SHADOW_TO_TINY_CANARY_APPROVED;
        } else {
            Optional<CapitalRung> next = policy.next(state.currentRungId());
            if (next.isEmpty()) {
                reasons.add(CanaryReasonCode.MAXIMUM_CAPITAL_RUNG_REACHED);
                return decision(CanaryAction.HOLD, reasons, request, "", "", now);
            }
            target = next.get();
            successAction = CanaryAction.ADVANCE_ONE_RUNG;
            successReason = CanaryReasonCode.CAPITAL_STEP_APPROVED;
        }

        if (!approvalAllows(request, target, now, reasons)) {
            return decision(CanaryAction.HOLD, reasons, request, "", "", now);
        }
        reasons.add(successReason);
        return decision(successAction, reasons, request, target.rungId(), "", now);
    }

    private SnapshotAssessment assessSnapshot(CanaryMetricSnapshot snapshot,
                                              boolean champion,
                                              Instant now) {
        EnumSet<CanaryReasonCode> reasons = EnumSet.noneOf(CanaryReasonCode.class);
        boolean integrity = false;
        boolean unavailable = false;
        if (!snapshot.contentAddressMatches()) {
            reasons.add(champion
                    ? CanaryReasonCode.CHAMPION_SNAPSHOT_CONTENT_DIGEST_MISMATCH
                    : CanaryReasonCode.CHALLENGER_SNAPSHOT_CONTENT_DIGEST_MISMATCH);
            integrity = true;
        }
        if (snapshot.completeness() == CanarySnapshotCompleteness.INCOMPLETE) {
            reasons.add(champion ? CanaryReasonCode.CHAMPION_SNAPSHOT_INCOMPLETE
                    : CanaryReasonCode.CHALLENGER_SNAPSHOT_INCOMPLETE);
            unavailable = true;
        } else if (snapshot.completeness() == CanarySnapshotCompleteness.ANOMALOUS) {
            reasons.add(champion ? CanaryReasonCode.CHAMPION_SNAPSHOT_ANOMALOUS
                    : CanaryReasonCode.CHALLENGER_SNAPSHOT_ANOMALOUS);
            integrity = true;
        }
        if (snapshot.observedAt().isAfter(now)) {
            reasons.add(champion ? CanaryReasonCode.CHAMPION_SNAPSHOT_NOT_YET_OBSERVED
                    : CanaryReasonCode.CHALLENGER_SNAPSHOT_NOT_YET_OBSERVED);
            integrity = true;
        }
        if (!snapshot.expiresAt().isAfter(now)) {
            reasons.add(champion ? CanaryReasonCode.CHAMPION_SNAPSHOT_EXPIRED
                    : CanaryReasonCode.CHALLENGER_SNAPSHOT_EXPIRED);
            unavailable = true;
        }
        return new SnapshotAssessment(reasons, integrity, unavailable,
                !integrity && !unavailable && snapshot.completeness() == CanarySnapshotCompleteness.COMPLETE);
    }

    private boolean addSampleAndCoverageReasons(CanaryDecisionRequest request,
                                                EnumSet<CanaryReasonCode> reasons) {
        boolean insufficient = false;
        if (metric(request.championSnapshot(), CanaryMetric.EXECUTED_SAMPLE_COUNT)
                .compareTo(BigDecimal.valueOf(policy.minimumSampleCount())) < 0) {
            reasons.add(CanaryReasonCode.CHAMPION_SAMPLE_COUNT_INSUFFICIENT);
            insufficient = true;
        }
        if (metric(request.challengerSnapshot(), CanaryMetric.EXECUTED_SAMPLE_COUNT)
                .compareTo(BigDecimal.valueOf(policy.minimumSampleCount())) < 0) {
            reasons.add(CanaryReasonCode.CHALLENGER_SAMPLE_COUNT_INSUFFICIENT);
            insufficient = true;
        }
        if (!hasCoverage(request.championSnapshot())) {
            reasons.add(CanaryReasonCode.CHAMPION_MARKET_STATE_COVERAGE_INSUFFICIENT);
            insufficient = true;
        }
        if (!hasCoverage(request.challengerSnapshot())) {
            reasons.add(CanaryReasonCode.CHALLENGER_MARKET_STATE_COVERAGE_INSUFFICIENT);
            insufficient = true;
        }
        return insufficient;
    }

    private boolean hasCoverage(CanaryMetricSnapshot snapshot) {
        for (MarketState state : MarketState.values()) {
            if (snapshot.marketStateSamples().get(state) < policy.minimumMarketStateSamples().get(state)) {
                return false;
            }
        }
        return true;
    }

    private void addPerformanceReasons(CanaryDecisionRequest request,
                                       EnumSet<CanaryReasonCode> reasons) {
        BigDecimal netAdvantage = metric(request.challengerSnapshot(), CanaryMetric.NET_RETURN_BPS)
                .subtract(metric(request.championSnapshot(), CanaryMetric.NET_RETURN_BPS));
        if (netAdvantage.compareTo(policy.minimumNetReturnAdvantageBps()) < 0) {
            reasons.add(CanaryReasonCode.RETURN_ADVANTAGE_BELOW_POLICY);
        }
        BigDecimal costRegression = metric(request.challengerSnapshot(), CanaryMetric.TOTAL_COST_BPS)
                .subtract(metric(request.championSnapshot(), CanaryMetric.TOTAL_COST_BPS));
        if (costRegression.compareTo(policy.maximumCostRegressionBps()) > 0) {
            reasons.add(CanaryReasonCode.COST_REGRESSION_ABOVE_POLICY);
        }
        BigDecimal drawdownRegression = metric(request.challengerSnapshot(), CanaryMetric.MAX_DRAWDOWN_BPS)
                .subtract(metric(request.championSnapshot(), CanaryMetric.MAX_DRAWDOWN_BPS));
        if (drawdownRegression.compareTo(policy.maximumDrawdownRegressionBps()) > 0) {
            reasons.add(CanaryReasonCode.DRAWDOWN_REGRESSION_ABOVE_POLICY);
        }
        BigDecimal tcaRegression = metric(request.challengerSnapshot(), CanaryMetric.TCA_SHORTFALL_BPS)
                .subtract(metric(request.championSnapshot(), CanaryMetric.TCA_SHORTFALL_BPS));
        if (tcaRegression.compareTo(policy.maximumTcaRegressionBps()) > 0) {
            reasons.add(CanaryReasonCode.TCA_REGRESSION_ABOVE_POLICY);
        }
    }

    private CanaryDecision evidenceUnavailableDecision(CanaryDecisionRequest request,
                                                       Optional<CapitalRung> currentRung,
                                                       EnumSet<CanaryReasonCode> reasons,
                                                       Instant now) {
        if (request.runState().stage() == CanaryStage.SHADOW) {
            return decision(CanaryAction.HOLD, reasons, request, "", "", now);
        }
        Optional<CapitalRung> previous = currentRung.flatMap(rung -> policy.previous(rung.rungId()));
        if (previous.isPresent()) {
            return decision(CanaryAction.DOWNGRADE_ONE_RUNG, reasons, request,
                    previous.get().rungId(), "", now);
        }
        return decision(CanaryAction.HALT, reasons, request, "", "", now);
    }

    private CanaryDecision performanceRegressionDecision(CanaryDecisionRequest request,
                                                         Optional<CapitalRung> currentRung,
                                                         EnumSet<CanaryReasonCode> reasons,
                                                         Instant now) {
        if (request.runState().stage() == CanaryStage.SHADOW) {
            return decision(CanaryAction.HOLD, reasons, request, "", "", now);
        }
        if (reasons.contains(CanaryReasonCode.RETURN_ADVANTAGE_BELOW_POLICY)
                || reasons.contains(CanaryReasonCode.DRAWDOWN_REGRESSION_ABOVE_POLICY)) {
            return decision(CanaryAction.ROLLBACK_TO_CHAMPION, reasons, request, "",
                    request.runState().championReleaseArtifactSha256(), now);
        }
        Optional<CapitalRung> previous = currentRung.flatMap(rung -> policy.previous(rung.rungId()));
        if (previous.isPresent()) {
            return decision(CanaryAction.DOWNGRADE_ONE_RUNG, reasons, request,
                    previous.get().rungId(), "", now);
        }
        return decision(CanaryAction.ROLLBACK_TO_CHAMPION, reasons, request, "",
                request.runState().championReleaseArtifactSha256(), now);
    }

    private boolean approvalAllows(CanaryDecisionRequest request,
                                   CapitalRung target,
                                   Instant now,
                                   EnumSet<CanaryReasonCode> reasons) {
        CapitalStepApproval approval = request.capitalApproval();
        if (approval == null) {
            reasons.add(CanaryReasonCode.APPROVAL_REQUIRED);
            return false;
        }
        String fromRung = request.runState().stage() == CanaryStage.SHADOW
                ? "SHADOW" : request.runState().currentRungId();
        if (!approval.policySha256().equals(policy.contentSha256())
                || !approval.deploymentScopeSha256().equals(
                request.runState().deploymentScope().contentSha256())
                || !approval.strategyName().equals(request.runState().strategyName())
                || !approval.championReleaseArtifactSha256().equals(
                request.runState().championReleaseArtifactSha256())
                || !approval.challengerReleaseArtifactSha256().equals(
                request.runState().challengerReleaseArtifactSha256())
                || !approval.evaluationWindowId().equals(request.championSnapshot().evaluationWindowId())
                || !approval.metricPairSha256().equals(request.metricPairSha256())
                || !approval.fromRungId().equals(fromRung)
                || !approval.toRungId().equals(target.rungId())) {
            reasons.add(CanaryReasonCode.APPROVAL_SCOPE_MISMATCH);
            return false;
        }
        Instant latestMetric = request.championSnapshot().observedAt()
                .isAfter(request.challengerSnapshot().observedAt())
                ? request.championSnapshot().observedAt() : request.challengerSnapshot().observedAt();
        if (approval.observedAt().isBefore(latestMetric)) {
            reasons.add(CanaryReasonCode.APPROVAL_PRECEDES_METRICS);
            return false;
        }
        CapitalApprovalVerification verification;
        try {
            verification = approvalVerifier.verify(approval, now);
        } catch (RuntimeException verifierFailure) {
            verification = CapitalApprovalVerification.VERIFIER_ERROR;
        }
        if (verification == CapitalApprovalVerification.VERIFIED) return true;
        reasons.add(switch (verification) {
            case CONTENT_DIGEST_MISMATCH -> CanaryReasonCode.APPROVAL_CONTENT_DIGEST_MISMATCH;
            case NOT_YET_VALID -> CanaryReasonCode.APPROVAL_NOT_YET_VALID;
            case EXPIRED -> CanaryReasonCode.APPROVAL_EXPIRED;
            case MALFORMED -> CanaryReasonCode.APPROVAL_MALFORMED;
            case UNTRUSTED_ISSUER -> CanaryReasonCode.APPROVAL_UNTRUSTED_ISSUER;
            case INVALID_SIGNATURE -> CanaryReasonCode.APPROVAL_SIGNATURE_INVALID;
            case VERIFIER_ERROR -> CanaryReasonCode.APPROVAL_VERIFIER_ERROR;
            case VERIFIED -> throw new IllegalStateException("verified approval was handled above");
        });
        return false;
    }

    private static boolean hasPerformanceRegression(EnumSet<CanaryReasonCode> reasons) {
        return reasons.contains(CanaryReasonCode.RETURN_ADVANTAGE_BELOW_POLICY)
                || reasons.contains(CanaryReasonCode.COST_REGRESSION_ABOVE_POLICY)
                || reasons.contains(CanaryReasonCode.DRAWDOWN_REGRESSION_ABOVE_POLICY)
                || reasons.contains(CanaryReasonCode.TCA_REGRESSION_ABOVE_POLICY);
    }

    private boolean durableHeadVerified(CanaryRunState state, Instant now) {
        try {
            return runStateVerifier.verifyCurrentHead(state, now) == CanaryRunStateVerification.VERIFIED;
        } catch (RuntimeException verifierFailure) {
            return false;
        }
    }

    private boolean metricEvidenceVerified(CanaryMetricSnapshot snapshot, Instant now) {
        try {
            return metricEvidenceVerifier.verify(snapshot, now) == CanaryMetricEvidenceVerification.VERIFIED;
        } catch (RuntimeException verifierFailure) {
            return false;
        }
    }

    private static BigDecimal metric(CanaryMetricSnapshot snapshot, CanaryMetric metric) {
        return snapshot.metrics().get(metric);
    }

    private static boolean validReleaseDigests(CanaryRunState state, CanaryDecisionRequest request) {
        return CanaryCanonical.isSha256(state.championReleaseArtifactSha256())
                && CanaryCanonical.isSha256(state.challengerReleaseArtifactSha256())
                && CanaryCanonical.isSha256(request.championSnapshot().releaseArtifactSha256())
                && CanaryCanonical.isSha256(request.challengerSnapshot().releaseArtifactSha256());
    }

    private static boolean stateBindingsMatch(CanaryRunState state, CanaryDecisionRequest request) {
        return state.strategyName().equals(request.championSnapshot().strategyName())
                && state.strategyName().equals(request.challengerSnapshot().strategyName())
                && state.championReleaseArtifactSha256().equals(
                request.championSnapshot().releaseArtifactSha256())
                && state.challengerReleaseArtifactSha256().equals(
                request.challengerSnapshot().releaseArtifactSha256());
    }

    private static boolean deploymentScopesAreValid(CanaryPolicy policy,
                                                     CanaryRunState state,
                                                     CanaryDecisionRequest request) {
        return policy.deploymentScope().contentAddressMatches()
                && state.deploymentScope().contentAddressMatches()
                && request.championSnapshot().deploymentScope().contentAddressMatches()
                && request.challengerSnapshot().deploymentScope().contentAddressMatches();
    }

    private static boolean deploymentScopesMatch(CanaryPolicy policy,
                                                  CanaryRunState state,
                                                  CanaryDecisionRequest request) {
        String expected = policy.deploymentScope().contentSha256();
        return expected.equals(state.deploymentScope().contentSha256())
                && expected.equals(request.championSnapshot().deploymentScope().contentSha256())
                && expected.equals(request.challengerSnapshot().deploymentScope().contentSha256());
    }

    private static boolean sameWindow(CanaryMetricSnapshot champion, CanaryMetricSnapshot challenger) {
        return champion.evaluationWindowId().equals(challenger.evaluationWindowId())
                && champion.windowStart().equals(challenger.windowStart())
                && champion.windowEnd().equals(challenger.windowEnd());
    }

    private CanaryDecision decision(CanaryAction action,
                                    EnumSet<CanaryReasonCode> reasons,
                                    CanaryDecisionRequest request,
                                    String targetRung,
                                    String rollbackRelease,
                                    Instant now) {
        List<CanaryReasonCode> ordered = List.copyOf(reasons);
        return CanaryDecision.issue(action, ordered, policy, request, targetRung, rollbackRelease, now);
    }

    private record SnapshotAssessment(EnumSet<CanaryReasonCode> reasons,
                                      boolean integrityFailure,
                                      boolean evidenceUnavailable,
                                      boolean usable) {
    }
}
