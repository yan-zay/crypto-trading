package com.tj.crypto.autonomy.canary;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CanaryDecisionEngineTest {
    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");
    private static final Instant WINDOW_START = NOW.minus(Duration.ofDays(31));
    private static final Instant WINDOW_END = NOW.minus(Duration.ofHours(2));
    private static final Instant OBSERVED_AT = NOW.minus(Duration.ofHours(1));
    private static final Instant EXPIRES_AT = NOW.plus(Duration.ofHours(1));
    private static final String STRATEGY = "golden-strategy";
    private static final String WINDOW_ID = "window-2026-07-24--2026-08-24";
    private static final String CHAMPION = sha256("champion-release");
    private static final String CHALLENGER = sha256("challenger-release");
    private static final DeploymentScope SCOPE = DeploymentScope.create(
            "account-1", "subaccount-canary", "BINANCE",
            sha256("btc-eth-universe-v1"), sha256("parent-capital-policy-v1"),
            sha256("hard-risk-policy-v7"), CanaryEnvironment.LIVE_CANARY);
    private static final DeploymentScope OTHER_SCOPE = DeploymentScope.create(
            "account-2", "subaccount-canary", "BINANCE",
            sha256("btc-eth-universe-v1"), sha256("parent-capital-policy-v1"),
            sha256("hard-risk-policy-v7"), CanaryEnvironment.LIVE_CANARY);
    private static final Instant STATE_OBSERVED_AT = NOW.minus(Duration.ofMinutes(5));
    private static final Instant STATE_EXPIRES_AT = NOW.plus(Duration.ofMinutes(5));

    private CanaryPolicy policy;
    private KeyPair approvalKeyPair;
    private CapitalApprovalVerifier approvalVerifier;
    private CanaryDecisionEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        policy = CanaryPolicy.conservativeV1(SCOPE);
        approvalKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        TrustedCapitalApprovalKey trustedKey = new TrustedCapitalApprovalKey(
                "external-capital-committee", "Ed25519",
                Base64.getEncoder().encodeToString(approvalKeyPair.getPublic().getEncoded()),
                NOW.minus(Duration.ofDays(2)), NOW.plus(Duration.ofDays(2)), null);
        approvalVerifier = new Ed25519CapitalApprovalVerifier(
                issuer -> trustedKey.issuer().equals(issuer) ? Optional.of(trustedKey) : Optional.empty());
        engine = new CanaryDecisionEngine(policy, approvalVerifier,
                (snapshot, evaluatedAt) -> CanaryMetricEvidenceVerification.VERIFIED,
                (state, evaluatedAt) -> CanaryRunStateVerification.VERIFIED,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void entersOnlyTheTinyFirstRungWithFreshCompleteMetricsAndExactExternalApproval() throws Exception {
        CanaryRunState state = shadowState(7);
        CanaryMetricSnapshot champion = championSnapshot();
        CanaryMetricSnapshot challenger = healthyChallengerSnapshot();
        CanaryDecisionRequest approved = approvedRequest(state, champion, challenger,
                "SHADOW", "TINY_1_BPS", OBSERVED_AT.plusSeconds(60), EXPIRES_AT);

        CanaryDecision decision = engine.evaluate(approved);

        assertThat(decision.action()).isEqualTo(CanaryAction.ENTER_TINY_CANARY);
        assertThat(decision.riskDirection()).isEqualTo(CanaryRiskDirection.INCREASE);
        assertThat(decision.targetRungId()).isEqualTo("TINY_1_BPS");
        assertThat(policy.firstRung().maxApprovedCapitalBps()).isEqualTo(1);
        assertThat(decision.reasonCodes()).containsExactly(CanaryReasonCode.SHADOW_TO_TINY_CANARY_APPROVED);
        assertThat(decision.deploymentScopeSha256()).isEqualTo(SCOPE.contentSha256());
        assertThat(decision.runStateSha256()).isEqualTo(state.contentSha256());
        assertThat(decision.decisionSha256()).matches("[0-9a-f]{64}");
    }

    @Test
    void holdsWithoutExternalApprovalAndCannotTreatMetricsAsSelfReportedPass() {
        CanaryRunState state = shadowState(1);

        CanaryDecision decision = engine.evaluate(new CanaryDecisionRequest(
                state, championSnapshot(), healthyChallengerSnapshot(), null));

        assertNoIncrease(decision);
        assertThat(decision.action()).isEqualTo(CanaryAction.HOLD);
        assertThat(decision.reasonCodes()).containsExactly(CanaryReasonCode.APPROVAL_REQUIRED);
    }

    @Test
    void signedApprovalCannotSkipAPredefinedCapitalRung() throws Exception {
        CanaryRunState state = shadowState(2);
        CanaryMetricSnapshot champion = championSnapshot();
        CanaryMetricSnapshot challenger = healthyChallengerSnapshot();
        CanaryDecisionRequest forgedJump = approvedRequest(state, champion, challenger,
                "SHADOW", "TINY_5_BPS", OBSERVED_AT.plusSeconds(60), EXPIRES_AT);

        CanaryDecision decision = engine.evaluate(forgedJump);

        assertNoIncrease(decision);
        assertThat(decision.action()).isEqualTo(CanaryAction.HOLD);
        assertThat(decision.reasonCodes()).contains(CanaryReasonCode.APPROVAL_SCOPE_MISMATCH);
    }

    @Test
    void advancesExactlyOneRungWithASeparatelyScopedApproval() throws Exception {
        CanaryRunState state = canaryState("TINY_1_BPS", 9);
        CanaryMetricSnapshot champion = championSnapshot();
        CanaryMetricSnapshot challenger = healthyChallengerSnapshot();
        CanaryDecisionRequest request = approvedRequest(state, champion, challenger,
                "TINY_1_BPS", "TINY_5_BPS", OBSERVED_AT.plusSeconds(60), EXPIRES_AT);

        CanaryDecision decision = engine.evaluate(request);

        assertThat(decision.action()).isEqualTo(CanaryAction.ADVANCE_ONE_RUNG);
        assertThat(decision.targetRungId()).isEqualTo("TINY_5_BPS");
        assertThat(decision.reasonCodes()).containsExactly(CanaryReasonCode.CAPITAL_STEP_APPROVED);
    }

    @Test
    void neverExpandsPastTheMaximumPredefinedRung() {
        CanaryRunState state = canaryState("BOUNDED_25_BPS", 12);

        CanaryDecision decision = engine.evaluate(new CanaryDecisionRequest(
                state, championSnapshot(), healthyChallengerSnapshot(), null));

        assertNoIncrease(decision);
        assertThat(decision.action()).isEqualTo(CanaryAction.HOLD);
        assertThat(decision.reasonCodes()).containsExactly(CanaryReasonCode.MAXIMUM_CAPITAL_RUNG_REACHED);
    }

    @Test
    void missingRawMetricDerivesIncompleteAndOnlyHoldsShadow() {
        EnumMap<CanaryMetric, BigDecimal> missingTca = healthyChallengerMetrics();
        missingTca.remove(CanaryMetric.TCA_SHORTFALL_BPS);
        CanaryMetricSnapshot incomplete = snapshot(CHALLENGER, missingTca, completeCoverage(),
                WINDOW_ID, WINDOW_START, WINDOW_END, OBSERVED_AT, EXPIRES_AT);

        CanaryDecision decision = engine.evaluate(new CanaryDecisionRequest(
                shadowState(1), championSnapshot(), incomplete, null));

        assertThat(incomplete.completeness()).isEqualTo(CanarySnapshotCompleteness.INCOMPLETE);
        assertNoIncrease(decision);
        assertThat(decision.action()).isEqualTo(CanaryAction.HOLD);
        assertThat(decision.reasonCodes()).contains(CanaryReasonCode.CHALLENGER_SNAPSHOT_INCOMPLETE);
    }

    @Test
    void expiredMetricsDowngradeAHigherCanaryRungButHaltAtTheFirstRung() {
        CanaryMetricSnapshot expiredChampion = snapshot(CHAMPION, championMetrics(), completeCoverage(),
                WINDOW_ID, WINDOW_START, WINDOW_END, OBSERVED_AT, NOW.minusSeconds(1));
        CanaryMetricSnapshot expiredChallenger = snapshot(CHALLENGER, healthyChallengerMetrics(),
                completeCoverage(), WINDOW_ID, WINDOW_START, WINDOW_END, OBSERVED_AT, NOW.minusSeconds(1));

        CanaryDecision downgrade = engine.evaluate(new CanaryDecisionRequest(
                canaryState("TINY_5_BPS", 5), expiredChampion, expiredChallenger, null));
        CanaryDecision halt = engine.evaluate(new CanaryDecisionRequest(
                canaryState("TINY_1_BPS", 6), expiredChampion, expiredChallenger, null));

        assertThat(downgrade.action()).isEqualTo(CanaryAction.DOWNGRADE_ONE_RUNG);
        assertThat(downgrade.targetRungId()).isEqualTo("TINY_1_BPS");
        assertThat(halt.action()).isEqualTo(CanaryAction.HALT);
        assertNoIncrease(downgrade);
        assertNoIncrease(halt);
        assertThat(halt.reasonCodes()).contains(CanaryReasonCode.CHALLENGER_SNAPSHOT_EXPIRED,
                CanaryReasonCode.CHAMPION_SNAPSHOT_EXPIRED);
    }

    @Test
    void tamperedContentAddressHaltsInsteadOfUsingTheClaimedMetrics() {
        CanaryMetricSnapshot source = healthyChallengerSnapshot();
        CanaryMetricSnapshot tampered = new CanaryMetricSnapshot(source.strategyName(), source.deploymentScope(),
                source.releaseArtifactSha256(), source.evaluationWindowId(), source.windowStart(),
                source.windowEnd(), source.observedAt(), source.expiresAt(), source.sourceManifestSha256(),
                source.metrics(), source.marketStateSamples(), sha256("not-the-content"));

        CanaryDecision decision = engine.evaluate(new CanaryDecisionRequest(
                canaryState("TINY_5_BPS", 2), championSnapshot(), tampered, null));

        assertThat(decision.action()).isEqualTo(CanaryAction.HALT);
        assertNoIncrease(decision);
        assertThat(decision.reasonCodes())
                .contains(CanaryReasonCode.CHALLENGER_SNAPSHOT_CONTENT_DIGEST_MISMATCH);
    }

    @Test
    void anomalousNegativeCostHaltsAndCannotBeMarkedCompleteByTheCaller() {
        EnumMap<CanaryMetric, BigDecimal> anomalous = healthyChallengerMetrics();
        anomalous.put(CanaryMetric.TOTAL_COST_BPS, new BigDecimal("-1"));
        CanaryMetricSnapshot snapshot = snapshot(CHALLENGER, anomalous, completeCoverage(),
                WINDOW_ID, WINDOW_START, WINDOW_END, OBSERVED_AT, EXPIRES_AT);

        CanaryDecision decision = engine.evaluate(new CanaryDecisionRequest(
                shadowState(1), championSnapshot(), snapshot, null));

        assertThat(snapshot.completeness()).isEqualTo(CanarySnapshotCompleteness.ANOMALOUS);
        assertThat(decision.action()).isEqualTo(CanaryAction.HALT);
        assertNoIncrease(decision);
        assertThat(decision.reasonCodes()).contains(CanaryReasonCode.CHALLENGER_SNAPSHOT_ANOMALOUS);
    }

    @Test
    void insufficientSamplesAndMarketStateCoverageCannotTriggerCapitalGrowth() {
        EnumMap<CanaryMetric, BigDecimal> sparseMetrics = healthyChallengerMetrics();
        sparseMetrics.put(CanaryMetric.EXECUTED_SAMPLE_COUNT, new BigDecimal("900"));
        EnumMap<MarketState, Long> sparseCoverage = completeCoverage();
        sparseCoverage.put(MarketState.TRENDING_DOWN, 50L);
        CanaryMetricSnapshot sparse = snapshot(CHALLENGER, sparseMetrics, sparseCoverage,
                WINDOW_ID, WINDOW_START, WINDOW_END, OBSERVED_AT, EXPIRES_AT);

        CanaryDecision decision = engine.evaluate(new CanaryDecisionRequest(
                shadowState(1), championSnapshot(), sparse, null));

        assertThat(decision.action()).isEqualTo(CanaryAction.HOLD);
        assertNoIncrease(decision);
        assertThat(decision.reasonCodes()).contains(
                CanaryReasonCode.CHALLENGER_SAMPLE_COUNT_INSUFFICIENT,
                CanaryReasonCode.CHALLENGER_MARKET_STATE_COVERAGE_INSUFFICIENT);
    }

    @Test
    void anyUnexplainedReconciliationEventHaltsTheCanary() {
        EnumMap<CanaryMetric, BigDecimal> unreconciled = healthyChallengerMetrics();
        unreconciled.put(CanaryMetric.UNEXPLAINED_RECONCILIATION_EVENTS, BigDecimal.ONE);

        CanaryDecision decision = engine.evaluate(new CanaryDecisionRequest(
                canaryState("TINY_5_BPS", 4),
                championSnapshot(), snapshot(CHALLENGER, unreconciled, completeCoverage(),
                WINDOW_ID, WINDOW_START, WINDOW_END, OBSERVED_AT, EXPIRES_AT), null));

        assertThat(decision.action()).isEqualTo(CanaryAction.HALT);
        assertNoIncrease(decision);
        assertThat(decision.reasonCodes()).containsExactly(
                CanaryReasonCode.UNEXPLAINED_RECONCILIATION_EVENTS);
    }

    @Test
    void validReturnOrDrawdownRegressionRollsBackToChampion() {
        EnumMap<CanaryMetric, BigDecimal> regression = healthyChallengerMetrics();
        regression.put(CanaryMetric.NET_RETURN_BPS, new BigDecimal("90"));
        regression.put(CanaryMetric.MAX_DRAWDOWN_BPS, new BigDecimal("130"));

        CanaryDecision decision = engine.evaluate(new CanaryDecisionRequest(
                canaryState("TINY_10_BPS", 11),
                championSnapshot(), snapshot(CHALLENGER, regression, completeCoverage(),
                WINDOW_ID, WINDOW_START, WINDOW_END, OBSERVED_AT, EXPIRES_AT), null));

        assertThat(decision.action()).isEqualTo(CanaryAction.ROLLBACK_TO_CHAMPION);
        assertThat(decision.rollbackReleaseArtifactSha256()).isEqualTo(CHAMPION);
        assertThat(decision.riskDirection()).isEqualTo(CanaryRiskDirection.DECREASE);
        assertThat(decision.reasonCodes()).contains(CanaryReasonCode.RETURN_ADVANTAGE_BELOW_POLICY,
                CanaryReasonCode.DRAWDOWN_REGRESSION_ABOVE_POLICY);
    }

    @Test
    void costAndTcaRegressionAutomaticallyDowngradeOneRung() {
        EnumMap<CanaryMetric, BigDecimal> regression = healthyChallengerMetrics();
        regression.put(CanaryMetric.TOTAL_COST_BPS, new BigDecimal("23"));
        regression.put(CanaryMetric.TCA_SHORTFALL_BPS, new BigDecimal("8"));

        CanaryDecision decision = engine.evaluate(new CanaryDecisionRequest(
                canaryState("TINY_10_BPS", 3),
                championSnapshot(), snapshot(CHALLENGER, regression, completeCoverage(),
                WINDOW_ID, WINDOW_START, WINDOW_END, OBSERVED_AT, EXPIRES_AT), null));

        assertThat(decision.action()).isEqualTo(CanaryAction.DOWNGRADE_ONE_RUNG);
        assertThat(decision.targetRungId()).isEqualTo("TINY_5_BPS");
        assertThat(decision.reasonCodes()).contains(CanaryReasonCode.COST_REGRESSION_ABOVE_POLICY,
                CanaryReasonCode.TCA_REGRESSION_ABOVE_POLICY);
    }

    @Test
    void invalidSignatureAndExpiredApprovalBothHold() throws Exception {
        CanaryRunState state = shadowState(1);
        CanaryMetricSnapshot champion = championSnapshot();
        CanaryMetricSnapshot challenger = healthyChallengerSnapshot();
        CanaryDecisionRequest unsignedRequest = new CanaryDecisionRequest(state, champion, challenger, null);
        CapitalStepApproval invalid = CapitalStepApproval.issue("approval-bad-signature",
                "external-capital-committee", policy.contentSha256(), SCOPE.contentSha256(),
                STRATEGY, CHAMPION, CHALLENGER,
                WINDOW_ID, unsignedRequest.metricPairSha256(), "SHADOW", "TINY_1_BPS",
                OBSERVED_AT.plusSeconds(60), EXPIRES_AT, "evidence://capital/bad-signature",
                sha256("bad-signature-evidence"), "Ed25519",
                Base64.getEncoder().encodeToString(new byte[64]));
        CanaryDecision badSignature = engine.evaluate(new CanaryDecisionRequest(state, champion, challenger,
                invalid));

        CanaryDecisionRequest expiredRequest = approvedRequest(state, champion, challenger,
                "SHADOW", "TINY_1_BPS", OBSERVED_AT.plusSeconds(60), NOW.minusSeconds(1));
        CanaryDecision expired = engine.evaluate(expiredRequest);

        assertThat(badSignature.action()).isEqualTo(CanaryAction.HOLD);
        assertThat(badSignature.reasonCodes()).contains(CanaryReasonCode.APPROVAL_SIGNATURE_INVALID);
        assertThat(expired.action()).isEqualTo(CanaryAction.HOLD);
        assertThat(expired.reasonCodes()).contains(CanaryReasonCode.APPROVAL_EXPIRED);
        assertNoIncrease(badSignature);
        assertNoIncrease(expired);
    }

    @Test
    void approvalMustBeObservedAfterTheMetricsItApproves() throws Exception {
        CanaryRunState state = shadowState(1);
        CanaryDecisionRequest request = approvedRequest(state, championSnapshot(), healthyChallengerSnapshot(),
                "SHADOW", "TINY_1_BPS", OBSERVED_AT.minusSeconds(1), EXPIRES_AT);

        CanaryDecision decision = engine.evaluate(request);

        assertThat(decision.action()).isEqualTo(CanaryAction.HOLD);
        assertThat(decision.reasonCodes()).contains(CanaryReasonCode.APPROVAL_PRECEDES_METRICS);
        assertNoIncrease(decision);
    }

    @Test
    void mismatchedComparisonWindowHaltsRatherThanMixingNonComparableEvidence() {
        CanaryMetricSnapshot otherWindow = snapshot(CHALLENGER, healthyChallengerMetrics(),
                completeCoverage(), "different-window", WINDOW_START, WINDOW_END, OBSERVED_AT, EXPIRES_AT);

        CanaryDecision decision = engine.evaluate(new CanaryDecisionRequest(
                shadowState(1), championSnapshot(), otherWindow, null));

        assertThat(decision.action()).isEqualTo(CanaryAction.HALT);
        assertThat(decision.reasonCodes()).contains(CanaryReasonCode.SNAPSHOT_WINDOW_MISMATCH);
        assertNoIncrease(decision);
    }

    @Test
    void crossAccountDeploymentScopeCannotMixRunStateAndMetrics() {
        CanaryMetricSnapshot crossAccount = snapshotForScope(OTHER_SCOPE, CHALLENGER,
                healthyChallengerMetrics(), completeCoverage(), WINDOW_ID, WINDOW_START,
                WINDOW_END, OBSERVED_AT, EXPIRES_AT);

        CanaryDecision decision = engine.evaluate(new CanaryDecisionRequest(
                shadowState(21), championSnapshot(), crossAccount, null));

        assertThat(decision.action()).isEqualTo(CanaryAction.HALT);
        assertThat(decision.reasonCodes()).contains(CanaryReasonCode.DEPLOYMENT_SCOPE_MISMATCH);
        assertNoIncrease(decision);
    }

    @Test
    void expiredOrTamperedPersistedRunStateHaltsBeforeMetricEvaluation() {
        CanaryRunState stale = CanaryRunState.shadow(STRATEGY, SCOPE, CHAMPION, CHALLENGER, 22,
                NOW.minus(Duration.ofHours(2)), NOW.minusSeconds(1), sha256("persisted-state-22"));
        CanaryRunState valid = shadowState(23);
        CanaryRunState tampered = new CanaryRunState(valid.strategyName(), valid.deploymentScope(),
                valid.championReleaseArtifactSha256(), valid.challengerReleaseArtifactSha256(),
                valid.stage(), valid.currentRungId(), valid.stateVersion(), valid.observedAt(), valid.expiresAt(),
                valid.persistenceEvidenceSha256(), sha256("tampered-state-head"));

        CanaryDecision staleDecision = engine.evaluate(new CanaryDecisionRequest(
                stale, championSnapshot(), healthyChallengerSnapshot(), null));
        CanaryDecision tamperedDecision = engine.evaluate(new CanaryDecisionRequest(
                tampered, championSnapshot(), healthyChallengerSnapshot(), null));

        assertThat(staleDecision.action()).isEqualTo(CanaryAction.HALT);
        assertThat(staleDecision.reasonCodes()).contains(CanaryReasonCode.RUN_STATE_EXPIRED);
        assertThat(tamperedDecision.action()).isEqualTo(CanaryAction.HALT);
        assertThat(tamperedDecision.reasonCodes())
                .contains(CanaryReasonCode.RUN_STATE_CONTENT_DIGEST_MISMATCH);
        assertNoIncrease(staleDecision);
        assertNoIncrease(tamperedDecision);
    }

    @Test
    void durableCurrentHeadAndTrustedMetricManifestAreMandatoryPorts() {
        CanaryDecisionEngine noMetricTruth = new CanaryDecisionEngine(policy, approvalVerifier,
                CanaryMetricEvidenceVerifier.notWired(),
                (state, evaluatedAt) -> CanaryRunStateVerification.VERIFIED,
                Clock.fixed(NOW, ZoneOffset.UTC));
        CanaryDecisionEngine noDurableHead = new CanaryDecisionEngine(policy, approvalVerifier,
                (snapshot, evaluatedAt) -> CanaryMetricEvidenceVerification.VERIFIED,
                CanaryRunStateVerifier.notWired(), Clock.fixed(NOW, ZoneOffset.UTC));
        CanaryDecisionRequest request = new CanaryDecisionRequest(
                shadowState(24), championSnapshot(), healthyChallengerSnapshot(), null);

        CanaryDecision metricDenied = noMetricTruth.evaluate(request);
        CanaryDecision stateDenied = noDurableHead.evaluate(request);

        assertThat(metricDenied.action()).isEqualTo(CanaryAction.HOLD);
        assertThat(metricDenied.reasonCodes()).contains(
                CanaryReasonCode.CHAMPION_METRIC_EVIDENCE_NOT_VERIFIED,
                CanaryReasonCode.CHALLENGER_METRIC_EVIDENCE_NOT_VERIFIED);
        assertThat(stateDenied.action()).isEqualTo(CanaryAction.HALT);
        assertThat(stateDenied.reasonCodes())
                .contains(CanaryReasonCode.RUN_STATE_DURABLE_HEAD_NOT_VERIFIED);
        assertNoIncrease(metricDenied);
        assertNoIncrease(stateDenied);
    }

    @Test
    void verifierExceptionsFailClosedWithoutCreatingAnIncreaseDecision() throws Exception {
        CanaryRunState state = shadowState(27);
        CanaryMetricSnapshot champion = championSnapshot();
        CanaryMetricSnapshot challenger = healthyChallengerSnapshot();
        CanaryDecisionRequest approved = approvedRequest(state, champion, challenger,
                "SHADOW", "TINY_1_BPS", OBSERVED_AT.plusSeconds(60), EXPIRES_AT);
        CanaryDecisionEngine brokenMetricVerifier = new CanaryDecisionEngine(policy, approvalVerifier,
                (snapshot, evaluatedAt) -> { throw new IllegalStateException("catalog unavailable"); },
                (runState, evaluatedAt) -> CanaryRunStateVerification.VERIFIED,
                Clock.fixed(NOW, ZoneOffset.UTC));
        CanaryDecisionEngine brokenStateVerifier = new CanaryDecisionEngine(policy, approvalVerifier,
                (snapshot, evaluatedAt) -> CanaryMetricEvidenceVerification.VERIFIED,
                (runState, evaluatedAt) -> { throw new IllegalStateException("head unavailable"); },
                Clock.fixed(NOW, ZoneOffset.UTC));
        CanaryDecisionEngine brokenApprovalVerifier = new CanaryDecisionEngine(policy,
                (approval, evaluatedAt) -> { throw new IllegalStateException("trust store unavailable"); },
                (snapshot, evaluatedAt) -> CanaryMetricEvidenceVerification.VERIFIED,
                (runState, evaluatedAt) -> CanaryRunStateVerification.VERIFIED,
                Clock.fixed(NOW, ZoneOffset.UTC));

        CanaryDecision metricDenied = brokenMetricVerifier.evaluate(approved);
        CanaryDecision stateDenied = brokenStateVerifier.evaluate(approved);
        CanaryDecision approvalDenied = brokenApprovalVerifier.evaluate(approved);

        assertThat(metricDenied.action()).isEqualTo(CanaryAction.HOLD);
        assertThat(stateDenied.action()).isEqualTo(CanaryAction.HALT);
        assertThat(approvalDenied.action()).isEqualTo(CanaryAction.HOLD);
        assertThat(approvalDenied.reasonCodes()).contains(CanaryReasonCode.APPROVAL_VERIFIER_ERROR);
        assertNoIncrease(metricDenied);
        assertNoIncrease(stateDenied);
        assertNoIncrease(approvalDenied);
    }

    @Test
    void externallySignedApprovalForAnotherAccountScopeStillCannotIncreaseCapital() throws Exception {
        CanaryRunState state = shadowState(25);
        CanaryMetricSnapshot champion = championSnapshot();
        CanaryMetricSnapshot challenger = healthyChallengerSnapshot();
        CapitalStepApproval wrongScope = signedApproval(state, champion, challenger,
                "SHADOW", "TINY_1_BPS", OBSERVED_AT.plusSeconds(60), EXPIRES_AT,
                OTHER_SCOPE.contentSha256());

        CanaryDecision decision = engine.evaluate(new CanaryDecisionRequest(
                state, champion, challenger, wrongScope));

        assertThat(decision.action()).isEqualTo(CanaryAction.HOLD);
        assertThat(decision.reasonCodes()).contains(CanaryReasonCode.APPROVAL_SCOPE_MISMATCH);
        assertNoIncrease(decision);
    }

    @Test
    void trustedClockPreventsReplayingExpiredApprovalByRollingBackCallerTime() throws Exception {
        Instant later = NOW.plus(Duration.ofHours(2));
        Instant evidenceExpiry = later.plus(Duration.ofHours(1));
        CanaryRunState state = CanaryRunState.shadow(STRATEGY, SCOPE, CHAMPION, CHALLENGER, 26,
                STATE_OBSERVED_AT, evidenceExpiry, sha256("persisted-state-26"));
        CanaryMetricSnapshot champion = snapshot(CHAMPION, championMetrics(), completeCoverage(),
                WINDOW_ID, WINDOW_START, WINDOW_END, OBSERVED_AT, evidenceExpiry);
        CanaryMetricSnapshot challenger = snapshot(CHALLENGER, healthyChallengerMetrics(), completeCoverage(),
                WINDOW_ID, WINDOW_START, WINDOW_END, OBSERVED_AT, evidenceExpiry);
        CanaryDecisionRequest request = approvedRequest(state, champion, challenger,
                "SHADOW", "TINY_1_BPS", OBSERVED_AT.plusSeconds(60), NOW.plus(Duration.ofHours(1)));
        CanaryDecisionEngine laterEngine = new CanaryDecisionEngine(policy, approvalVerifier,
                (snapshot, evaluatedAt) -> CanaryMetricEvidenceVerification.VERIFIED,
                (runState, evaluatedAt) -> CanaryRunStateVerification.VERIFIED,
                Clock.fixed(later, ZoneOffset.UTC));

        CanaryDecision decision = laterEngine.evaluate(request);

        assertThat(decision.action()).isEqualTo(CanaryAction.HOLD);
        assertThat(decision.reasonCodes()).contains(CanaryReasonCode.APPROVAL_EXPIRED);
        assertNoIncrease(decision);
    }

    @Test
    void sameImmutableInputsProduceTheSameContentAddressedDecision() throws Exception {
        CanaryRunState state = shadowState(42);
        CanaryMetricSnapshot champion = championSnapshot();
        CanaryMetricSnapshot challenger = healthyChallengerSnapshot();
        CanaryDecisionRequest request = approvedRequest(state, champion, challenger,
                "SHADOW", "TINY_1_BPS", OBSERVED_AT.plusSeconds(60), EXPIRES_AT);

        CanaryDecision first = engine.evaluate(request);
        CanaryDecision second = engine.evaluate(request);

        assertThat(second).isEqualTo(first);
        assertThat(second.decisionSha256()).isEqualTo(first.decisionSha256());
    }

    private CanaryDecisionRequest approvedRequest(CanaryRunState state,
                                                  CanaryMetricSnapshot champion,
                                                  CanaryMetricSnapshot challenger,
                                                  String fromRung,
                                                  String toRung,
                                                  Instant approvalObservedAt,
                                                  Instant approvalExpiresAt) throws Exception {
        CapitalStepApproval signed = signedApproval(state, champion, challenger, fromRung, toRung,
                approvalObservedAt, approvalExpiresAt, SCOPE.contentSha256());
        return new CanaryDecisionRequest(state, champion, challenger, signed);
    }

    private CapitalStepApproval signedApproval(CanaryRunState state,
                                               CanaryMetricSnapshot champion,
                                               CanaryMetricSnapshot challenger,
                                               String fromRung,
                                               String toRung,
                                               Instant approvalObservedAt,
                                               Instant approvalExpiresAt,
                                               String deploymentScopeSha256) throws Exception {
        CanaryDecisionRequest unsigned = new CanaryDecisionRequest(state, champion, challenger, null);
        CapitalStepApproval provisional = CapitalStepApproval.issue("approval-" + state.stateVersion()
                        + "-" + fromRung + "-" + toRung,
                "external-capital-committee", policy.contentSha256(), deploymentScopeSha256,
                STRATEGY, CHAMPION, CHALLENGER,
                WINDOW_ID, unsigned.metricPairSha256(), fromRung, toRung, approvalObservedAt,
                approvalExpiresAt, "evidence://capital/" + state.stateVersion(),
                sha256("external-evidence-" + state.stateVersion()), "Ed25519",
                Base64.getEncoder().encodeToString(new byte[64]));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(approvalKeyPair.getPrivate());
        signer.update(provisional.signaturePayload());
        CapitalStepApproval signed = CapitalStepApproval.issue(provisional.approvalId(), provisional.issuer(),
                provisional.policySha256(), provisional.deploymentScopeSha256(), provisional.strategyName(),
                provisional.championReleaseArtifactSha256(), provisional.challengerReleaseArtifactSha256(),
                provisional.evaluationWindowId(), provisional.metricPairSha256(), provisional.fromRungId(),
                provisional.toRungId(), provisional.observedAt(), provisional.expiresAt(),
                provisional.evidenceUri(), provisional.evidenceSha256(), provisional.signatureAlgorithm(),
                Base64.getEncoder().encodeToString(signer.sign()));
        return signed;
    }

    private static CanaryMetricSnapshot championSnapshot() {
        return snapshot(CHAMPION, championMetrics(), completeCoverage(), WINDOW_ID,
                WINDOW_START, WINDOW_END, OBSERVED_AT, EXPIRES_AT);
    }

    private static CanaryMetricSnapshot healthyChallengerSnapshot() {
        return snapshot(CHALLENGER, healthyChallengerMetrics(), completeCoverage(), WINDOW_ID,
                WINDOW_START, WINDOW_END, OBSERVED_AT, EXPIRES_AT);
    }

    private static CanaryMetricSnapshot snapshot(String release,
                                                 Map<CanaryMetric, BigDecimal> metrics,
                                                 Map<MarketState, Long> coverage,
                                                 String windowId,
                                                 Instant windowStart,
                                                 Instant windowEnd,
                                                 Instant observedAt,
                                                 Instant expiresAt) {
        return snapshotForScope(SCOPE, release, metrics, coverage, windowId, windowStart, windowEnd,
                observedAt, expiresAt);
    }

    private static CanaryMetricSnapshot snapshotForScope(DeploymentScope scope,
                                                         String release,
                                                         Map<CanaryMetric, BigDecimal> metrics,
                                                         Map<MarketState, Long> coverage,
                                                         String windowId,
                                                         Instant windowStart,
                                                         Instant windowEnd,
                                                         Instant observedAt,
                                                         Instant expiresAt) {
        return CanaryMetricSnapshot.capture(STRATEGY, scope, release, windowId, windowStart, windowEnd,
                observedAt, expiresAt, sha256("manifest-" + release), metrics, coverage);
    }

    private static CanaryRunState shadowState(long version) {
        return CanaryRunState.shadow(STRATEGY, SCOPE, CHAMPION, CHALLENGER, version,
                STATE_OBSERVED_AT, STATE_EXPIRES_AT, sha256("persisted-state-" + version));
    }

    private static CanaryRunState canaryState(String rungId, long version) {
        return CanaryRunState.canary(STRATEGY, SCOPE, CHAMPION, CHALLENGER, rungId, version,
                STATE_OBSERVED_AT, STATE_EXPIRES_AT, sha256("persisted-state-" + version));
    }

    private static EnumMap<CanaryMetric, BigDecimal> championMetrics() {
        EnumMap<CanaryMetric, BigDecimal> metrics = new EnumMap<>(CanaryMetric.class);
        metrics.put(CanaryMetric.NET_RETURN_BPS, new BigDecimal("100"));
        metrics.put(CanaryMetric.TOTAL_COST_BPS, new BigDecimal("20"));
        metrics.put(CanaryMetric.MAX_DRAWDOWN_BPS, new BigDecimal("100"));
        metrics.put(CanaryMetric.TCA_SHORTFALL_BPS, new BigDecimal("5"));
        metrics.put(CanaryMetric.UNEXPLAINED_RECONCILIATION_EVENTS, BigDecimal.ZERO);
        metrics.put(CanaryMetric.EXECUTED_SAMPLE_COUNT, new BigDecimal("1200"));
        return metrics;
    }

    private static EnumMap<CanaryMetric, BigDecimal> healthyChallengerMetrics() {
        EnumMap<CanaryMetric, BigDecimal> metrics = new EnumMap<>(CanaryMetric.class);
        metrics.put(CanaryMetric.NET_RETURN_BPS, new BigDecimal("110"));
        metrics.put(CanaryMetric.TOTAL_COST_BPS, new BigDecimal("20.5"));
        metrics.put(CanaryMetric.MAX_DRAWDOWN_BPS, new BigDecimal("105"));
        metrics.put(CanaryMetric.TCA_SHORTFALL_BPS, new BigDecimal("5.5"));
        metrics.put(CanaryMetric.UNEXPLAINED_RECONCILIATION_EVENTS, BigDecimal.ZERO);
        metrics.put(CanaryMetric.EXECUTED_SAMPLE_COUNT, new BigDecimal("1200"));
        return metrics;
    }

    private static EnumMap<MarketState, Long> completeCoverage() {
        EnumMap<MarketState, Long> coverage = new EnumMap<>(MarketState.class);
        coverage.put(MarketState.TRENDING_UP, 200L);
        coverage.put(MarketState.TRENDING_DOWN, 200L);
        coverage.put(MarketState.RANGE_BOUND, 200L);
        coverage.put(MarketState.HIGH_VOLATILITY, 200L);
        return coverage;
    }

    private static void assertNoIncrease(CanaryDecision decision) {
        assertThat(decision.riskDirection()).isNotEqualTo(CanaryRiskDirection.INCREASE);
        assertThat(decision.action()).isNotIn(CanaryAction.ENTER_TINY_CANARY,
                CanaryAction.ADVANCE_ONE_RUNG);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
