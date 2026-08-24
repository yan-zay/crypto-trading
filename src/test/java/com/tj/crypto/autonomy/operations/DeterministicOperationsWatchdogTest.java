package com.tj.crypto.autonomy.operations;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicOperationsWatchdogTest {
    private static final long NOW = 1_000_000L;
    private static final String SUBJECT = "1".repeat(64);
    private static final String PAYLOAD = "2".repeat(64);
    private static final String PURPOSE = "3".repeat(64);
    private static final String VERIFIER = "independent-operator-idp";
    private static final String OBSERVATION_VERIFIER = "independent-fact-verifier";
    private static final String HEAD_AUTHORITY = "durable-head-store";

    private final OperationsWatchdogPolicy policy = policy();
    private final MutableClock clock = new MutableClock(NOW);
    private final DeterministicOperationsWatchdog watchdog = new DeterministicOperationsWatchdog(
            policy, clock, headPort(), this::verifyObservation, this::verifyTakeover,
            this::verifyApproval, this::verifyCompletion, persistedControlVerifier());

    @Test
    void startsHaltedAndCoversEveryRequiredAuthoritativeStream() {
        OperationsSnapshot initial = watchdog.initialState();

        assertThat(initial.state()).isEqualTo(OperationsState.HALTED);
        assertThat(initial.latestObservations()).isEmpty();
        assertThat(watchdog.tick(initial, NOW).actionIntents())
                .extracting(OperationsActionIntent::type)
                .contains(OperationsActionType.REQUEST_KILL_SWITCH_HALT,
                        OperationsActionType.HOLD_RISK_INCREASE);

        OperationsSnapshot observed = withAllHealthyFacts(initial, NOW);

        assertThat(observed.latestObservations().keySet())
                .containsExactlyInAnyOrder(OperationsObservationKind.values());
        assertThat(observed.state()).isEqualTo(OperationsState.HALTED);
        assertThat(observed.hasValidDigest()).isTrue();
    }

    @Test
    void unknownExpiredWrongSourceAndTamperedInputsAllFailClosed() {
        OperationsSnapshot base = watchdog.initialState();
        OperationsObservation valid = observation(
                OperationsObservationKind.MARKET_DATA, 1, OperationsHealth.HEALTHY, NOW);
        OperationsObservation unknown = new OperationsObservation("UNDECLARED_SOURCE",
                "market-data-monitor", 1, NOW - 100, NOW + 10_000,
                OperationsHealth.HEALTHY, SUBJECT, PAYLOAD, "9".repeat(64), "4".repeat(64));
        OperationsObservation expired = OperationsObservation.create(
                OperationsObservationKind.MARKET_DATA, "market-data-monitor", 1,
                NOW - 20_000, NOW - 1, OperationsHealth.HEALTHY, SUBJECT, PAYLOAD,
                "9".repeat(64));
        OperationsObservation wrongSource = OperationsObservation.create(
                OperationsObservationKind.MARKET_DATA, "agent", 1,
                NOW - 100, NOW + 10_000, OperationsHealth.HEALTHY, SUBJECT, PAYLOAD,
                "9".repeat(64));
        OperationsObservation tampered = new OperationsObservation(valid.inputType(), valid.source(),
                valid.sequence(), valid.observedAtMs(), valid.expiresAtMs(), valid.health(),
                valid.subjectSha256(), "5".repeat(64), valid.attestationSha256(),
                valid.observationSha256());

        assertHalted(watchdog.observe(base, unknown, NOW), OperationsReason.INPUT_UNKNOWN);
        assertHalted(watchdog.observe(base, expired, NOW), OperationsReason.INPUT_EXPIRED);
        assertHalted(watchdog.observe(base, wrongSource, NOW),
                OperationsReason.INPUT_SOURCE_MISMATCH);
        assertHalted(watchdog.observe(base, tampered, NOW),
                OperationsReason.INPUT_DIGEST_MISMATCH);
    }

    @Test
    void selfAssertedSourceWithoutTrustedAttestationFailsClosed() {
        DeterministicOperationsWatchdog untrustedSource = new DeterministicOperationsWatchdog(
                policy, clock, headPort(),
                (observation, now) -> OperationsObservationVerification.untrusted("bad signature"),
                this::verifyTakeover, this::verifyApproval, this::verifyCompletion,
                persistedControlVerifier());

        assertHalted(untrustedSource.observe(untrustedSource.initialState(), observation(
                        OperationsObservationKind.MARKET_DATA, 1,
                        OperationsHealth.HEALTHY, NOW), NOW),
                OperationsReason.INPUT_SOURCE_MISMATCH);
    }

    @Test
    void detectsIdempotentReplayConflictOutOfOrderAndSequenceGap() {
        OperationsSnapshot base = watchdog.initialState();
        OperationsObservation first = observation(
                OperationsObservationKind.OMS, 1, OperationsHealth.HEALTHY, NOW);
        OperationsDecision accepted = watchdog.observe(base, first, NOW);

        OperationsDecision replay = watchdog.observe(accepted.snapshot(), first, NOW);
        assertThat(replay.accepted()).isTrue();
        assertThat(replay.reason()).isEqualTo(OperationsReason.IDEMPOTENT_REPLAY);
        assertThat(replay.mustApply()).isFalse();
        assertThat(replay.snapshot().revision()).isEqualTo(accepted.snapshot().revision());
        assertThat(replay.snapshot().latestObservations().get(
                OperationsObservationKind.OMS).sequence()).isEqualTo(1);

        OperationsObservation conflict = OperationsObservation.create(
                OperationsObservationKind.OMS, "oms-monitor", 1, NOW - 100, NOW + 30_000,
                OperationsHealth.HEALTHY, SUBJECT, "6".repeat(64), "9".repeat(64));
        assertHalted(watchdog.observe(accepted.snapshot(), conflict, NOW),
                OperationsReason.INPUT_CONFLICT);

        OperationsObservation second = observation(
                OperationsObservationKind.OMS, 2, OperationsHealth.HEALTHY, NOW);
        OperationsSnapshot afterSecond = watchdog.observe(accepted.snapshot(), second, NOW).snapshot();
        OperationsObservation old = OperationsObservation.create(
                OperationsObservationKind.OMS, "oms-monitor", 1, NOW - 50, NOW + 30_000,
                OperationsHealth.HEALTHY, SUBJECT, "7".repeat(64), "9".repeat(64));
        assertHalted(watchdog.observe(afterSecond, old, NOW),
                OperationsReason.INPUT_OUT_OF_ORDER);

        OperationsObservation gap = observation(
                OperationsObservationKind.OMS, 3, OperationsHealth.HEALTHY, NOW);
        assertHalted(watchdog.observe(accepted.snapshot(), gap, NOW),
                OperationsReason.INPUT_SEQUENCE_GAP);
    }

    @Test
    void failedOrUnknownAuthoritativeHealthHaltsAndTickCatchesExpiry() {
        OperationsSnapshot normal = normalState(NOW);
        OperationsObservation failedVenue = observation(
                OperationsObservationKind.VENUE_TRUTH, 2, OperationsHealth.FAILED, NOW);

        OperationsDecision failure = watchdog.observe(normal, failedVenue, NOW);
        assertHalted(failure, OperationsReason.HEALTH_FAILED);
        assertThat(failure.snapshot().latestObservations().get(
                OperationsObservationKind.VENUE_TRUTH).health())
                .isEqualTo(OperationsHealth.FAILED);

        clock.setMillis(NOW + 30_001);
        OperationsDecision expired = watchdog.tick(normal, NOW + 30_001);
        assertHalted(expired, OperationsReason.REQUIRED_FACT_EXPIRED);
    }

    @Test
    void trustedClockRollbackCannotReviveExpiredFactsOrApprovals() {
        OperationsSnapshot normal = normalState(NOW);
        clock.setMillis(NOW + 30_001);
        OperationsSnapshot expired = watchdog.tick(normal, NOW + 30_001).snapshot();

        clock.setMillis(NOW);
        OperationsDecision rollback = watchdog.tick(expired, NOW);

        assertHalted(rollback, OperationsReason.TIME_AUTHORITY_MISMATCH);
        assertThat(rollback.snapshot().lastEvaluatedAtMs())
                .isGreaterThanOrEqualTo(expired.lastEvaluatedAtMs());
    }

    @Test
    void callerSuppliedTimeMustExactlyMatchTrustedClock() {
        OperationsDecision forgedTime = watchdog.tick(watchdog.initialState(), NOW + 1);

        assertHalted(forgedTime, OperationsReason.TIME_AUTHORITY_MISMATCH);
    }

    @Test
    void eachTransitionSamplesTrustedClockOnceAndFirstReadFailureHalts() {
        OperationsSnapshot normal = normalState(NOW);
        FailingAfterReadsClock oneReadOnly = new FailingAfterReadsClock(NOW, 1);
        DeterministicOperationsWatchdog singleReadWatchdog = watchdogWithClock(oneReadOnly);

        OperationsDecision accepted = singleReadWatchdog.observe(normal, observation(
                OperationsObservationKind.OMS, 2, OperationsHealth.HEALTHY, NOW), NOW);

        assertThat(accepted.snapshot().state()).isEqualTo(OperationsState.NORMAL);
        assertThat(oneReadOnly.readCount()).isEqualTo(1);

        FailingAfterReadsClock unavailable = new FailingAfterReadsClock(NOW, 0);
        OperationsDecision failedClock = watchdogWithClock(unavailable).tick(normal, NOW);
        assertHalted(failedClock, OperationsReason.TIME_AUTHORITY_MISMATCH);
        assertThat(failedClock.actionIntents()).extracting(OperationsActionIntent::type)
                .doesNotContain(OperationsActionType.CONTINUE_BOUNDED_OPERATION);
    }

    @Test
    void clockRollbackCannotReviveExpiredDualControlApproval() {
        OperationsSnapshot halted = withAllHealthyFacts(watchdog.initialState(), NOW);
        RecoveryRequest request = recoveryRequest(
                halted, "approval-expiry", RecoveryTarget.DEGRADED, NOW);
        OperationsSnapshot pending = watchdog.requestRecovery(halted, request, NOW).snapshot();
        OperationsSnapshot approved = watchdog.approveRecovery(pending,
                approval(request, "alice", NOW, "a".repeat(64)), NOW).snapshot();

        clock.setMillis(NOW + 10_001);
        OperationsDecision expired = watchdog.tick(approved, NOW + 10_001);
        assertHalted(expired, OperationsReason.INPUT_EXPIRED);

        clock.setMillis(NOW);
        OperationsDecision rollback = watchdog.tick(expired.snapshot(), NOW);
        assertHalted(rollback, OperationsReason.TIME_AUTHORITY_MISMATCH);
    }

    @Test
    void agentHeartbeatCanDegradeButCanNeverRecoverStateByItself() {
        OperationsSnapshot normal = normalState(NOW);
        OperationsDecision degraded = watchdog.observe(normal, observation(
                OperationsObservationKind.AGENT_HEARTBEAT, 2,
                OperationsHealth.DEGRADED, NOW), NOW);

        assertThat(degraded.snapshot().state()).isEqualTo(OperationsState.DEGRADED);
        assertThat(degraded.actionIntents()).extracting(OperationsActionIntent::type)
                .contains(OperationsActionType.DISABLE_AGENT_ACTIONS,
                        OperationsActionType.HOLD_RISK_INCREASE);

        OperationsDecision healthyAgain = watchdog.observe(degraded.snapshot(), observation(
                OperationsObservationKind.AGENT_HEARTBEAT, 3,
                OperationsHealth.HEALTHY, NOW), NOW);

        assertThat(healthyAgain.snapshot().state()).isEqualTo(OperationsState.DEGRADED);
        assertThat(healthyAgain.actionIntents()).extracting(OperationsActionIntent::type)
                .doesNotContain(OperationsActionType.CONTINUE_BOUNDED_OPERATION);
    }

    @Test
    void healthyFactsNeverAutomaticallyReleaseHalt() {
        OperationsSnapshot normal = normalState(NOW);
        OperationsSnapshot halted = watchdog.observe(normal, observation(
                OperationsObservationKind.MARKET_DATA, 2, OperationsHealth.FAILED, NOW), NOW)
                .snapshot();

        OperationsDecision healthyAgain = watchdog.observe(halted, observation(
                OperationsObservationKind.MARKET_DATA, 3, OperationsHealth.HEALTHY, NOW), NOW);

        assertThat(healthyAgain.snapshot().state()).isEqualTo(OperationsState.HALTED);
        assertThat(healthyAgain.actionIntents()).extracting(OperationsActionIntent::type)
                .contains(OperationsActionType.REQUEST_KILL_SWITCH_HALT);
    }

    @Test
    void haltCannotRequestNormalAndRecoveryRequiresTwoStepDualControl() {
        OperationsSnapshot halted = withAllHealthyFacts(watchdog.initialState(), NOW);
        RecoveryRequest skippedStep = recoveryRequest(
                halted, "skip", RecoveryTarget.NORMAL, NOW);

        assertHalted(watchdog.requestRecovery(halted, skippedStep, NOW),
                OperationsReason.RECOVERY_REQUEST_INVALID);
        RecoveryRequest unknownTarget = recoveryRequest(halted, "unknown-target", null, NOW);
        assertHalted(watchdog.requestRecovery(halted, unknownTarget, NOW),
                OperationsReason.RECOVERY_REQUEST_INVALID);

        OperationsDecision degraded = recover(
                halted, "recover-degraded", RecoveryTarget.DEGRADED, NOW);
        assertThat(degraded.snapshot().state()).isEqualTo(OperationsState.DEGRADED);
        assertThat(degraded.reason()).isEqualTo(
                OperationsReason.RECOVERY_COMPLETED_TO_DEGRADED);

        OperationsDecision normal = recover(
                degraded.snapshot(), "recover-normal", RecoveryTarget.NORMAL, NOW);
        assertThat(normal.snapshot().state()).isEqualTo(OperationsState.NORMAL);
        assertThat(normal.reason()).isEqualTo(OperationsReason.RECOVERY_COMPLETED_TO_NORMAL);
    }

    @Test
    void oneApprovalCannotCompleteAndSecondApprovalDoesNotAutoComplete() {
        OperationsSnapshot halted = withAllHealthyFacts(watchdog.initialState(), NOW);
        RecoveryRequest request = recoveryRequest(
                halted, "recovery-one", RecoveryTarget.DEGRADED, NOW);
        OperationsDecision requested = watchdog.requestRecovery(halted, request, NOW);
        assertThat(requested.snapshot().recoveryOriginState()).isEqualTo(OperationsState.HALTED);
        assertThat(requested.actionIntents()).extracting(OperationsActionIntent::type)
                .contains(OperationsActionType.REQUEST_KILL_SWITCH_HALT);
        OperationsSnapshot pending = requested.snapshot();
        RecoveryApproval first = approval(request, "alice", NOW, "a".repeat(64));
        OperationsDecision approved = watchdog.approveRecovery(pending, first, NOW);

        OperationsDecision tooSoon = watchdog.completeRecovery(approved.snapshot(),
                completion(request, approved.snapshot(), "alice", NOW), NOW);
        assertThat(tooSoon.accepted()).isFalse();
        assertThat(tooSoon.reason()).isEqualTo(
                OperationsReason.RECOVERY_DUAL_CONTROL_PENDING);
        assertThat(tooSoon.snapshot().state()).isEqualTo(OperationsState.RECOVERY_PENDING);

        RecoveryApproval second = approval(request, "bob", NOW, "b".repeat(64));
        OperationsDecision dual = watchdog.approveRecovery(approved.snapshot(), second, NOW);
        assertThat(dual.snapshot().state()).isEqualTo(OperationsState.RECOVERY_PENDING);
        assertThat(dual.reason()).isEqualTo(
                OperationsReason.RECOVERY_CONFIRMATION_REQUIRED);
    }

    @Test
    void duplicateOperatorOrReplayArtifactInvalidatesRecovery() {
        OperationsSnapshot halted = withAllHealthyFacts(watchdog.initialState(), NOW);
        RecoveryRequest request = recoveryRequest(
                halted, "recovery-conflict", RecoveryTarget.DEGRADED, NOW);
        OperationsSnapshot pending = watchdog.requestRecovery(halted, request, NOW).snapshot();
        OperationsSnapshot once = watchdog.approveRecovery(pending,
                approval(request, "alice", NOW, "a".repeat(64)), NOW).snapshot();
        RecoveryApproval secondAlice = approval(request, "alice", NOW + 1, "c".repeat(64));

        clock.setMillis(NOW + 1);
        assertHalted(watchdog.approveRecovery(once, secondAlice, NOW + 1),
                OperationsReason.RECOVERY_APPROVAL_CONFLICT);
    }

    @Test
    void untrustedOrMisboundApprovalFailsClosed() {
        DeterministicOperationsWatchdog untrusted = new DeterministicOperationsWatchdog(
                policy, clock, headPort(), this::verifyObservation, this::verifyTakeover,
                (approval, request, now) -> RecoveryApprovalVerification.untrusted("bad signature"),
                this::verifyCompletion, persistedControlVerifier());
        OperationsSnapshot halted = withAllHealthyFacts(untrusted,
                untrusted.initialState(), NOW);
        RecoveryRequest request = recoveryRequest(
                halted, "untrusted", RecoveryTarget.DEGRADED, NOW);
        OperationsSnapshot pending = untrusted.requestRecovery(halted, request, NOW).snapshot();

        assertHalted(untrusted.approveRecovery(pending,
                approval(request, "alice", NOW, "a".repeat(64)), NOW),
                OperationsReason.RECOVERY_APPROVAL_INVALID);
    }

    @Test
    void independentlyVerifiedManualTakeoverNeverAutoReleases() {
        OperationsSnapshot normal = normalState(NOW);
        ManualTakeoverCommand command = ManualTakeoverCommand.create(
                "takeover-1", "operator-a", NOW - 10, NOW + 20_000, SUBJECT,
                normal.snapshotSha256(), "d".repeat(64));

        OperationsDecision takeover = watchdog.enterManualTakeover(normal, command, NOW);
        assertThat(takeover.snapshot().state()).isEqualTo(OperationsState.MANUAL_TAKEOVER);
        assertThat(takeover.snapshot().manualTakeoverLease()).satisfies(lease -> {
            assertThat(lease.operatorId()).isEqualTo("operator-a");
            assertThat(lease.verifierId()).isEqualTo(VERIFIER);
            assertThat(lease.replayReservationId()).isNotBlank();
        });
        assertThat(takeover.actionIntents()).extracting(OperationsActionIntent::type)
                .contains(OperationsActionType.HAND_CONTROL_TO_OPERATOR,
                        OperationsActionType.HOLD_RISK_INCREASE);

        OperationsDecision retry = watchdog.enterManualTakeover(takeover.snapshot(), command, NOW);
        assertThat(retry.accepted()).isTrue();
        assertThat(retry.mustApply()).isFalse();
        assertThat(retry.snapshot()).isEqualTo(takeover.snapshot());

        RecoveryRequest exitManual = recoveryRequest(takeover.snapshot(), "exit-manual",
                RecoveryTarget.DEGRADED, NOW);
        OperationsDecision pendingRecovery = watchdog.requestRecovery(
                takeover.snapshot(), exitManual, NOW);
        assertThat(pendingRecovery.snapshot().recoveryOriginState())
                .isEqualTo(OperationsState.MANUAL_TAKEOVER);
        assertThat(pendingRecovery.snapshot().manualTakeoverLease())
                .isEqualTo(takeover.snapshot().manualTakeoverLease());
        assertThat(pendingRecovery.actionIntents()).extracting(OperationsActionIntent::type)
                .contains(OperationsActionType.HAND_CONTROL_TO_OPERATOR);

        OperationsDecision healthy = watchdog.observe(takeover.snapshot(), observation(
                OperationsObservationKind.OMS, 2, OperationsHealth.HEALTHY, NOW), NOW);
        assertThat(healthy.snapshot().state()).isEqualTo(OperationsState.MANUAL_TAKEOVER);

        clock.setMillis(NOW + 20_000);
        assertHalted(watchdog.tick(healthy.snapshot(), NOW + 20_000),
                OperationsReason.INPUT_EXPIRED);
    }

    @Test
    void missingCurrentSnapshotAndUnsignedRecoveryCompletionFailClosed() {
        assertHalted(watchdog.tick(null, NOW), OperationsReason.DURABLE_HEAD_NOT_CURRENT);

        DeterministicOperationsWatchdog unsignedCompletion = new DeterministicOperationsWatchdog(
                policy, clock, headPort(), this::verifyObservation, this::verifyTakeover,
                this::verifyApproval, (command, request, now) -> RecoveryCompletionVerification.untrusted(
                        "missing human signature"), persistedControlVerifier());
        OperationsSnapshot halted = withAllHealthyFacts(
                unsignedCompletion, unsignedCompletion.initialState(), NOW);
        RecoveryRequest request = recoveryRequest(
                halted, "unsigned-completion", RecoveryTarget.DEGRADED, NOW);
        OperationsSnapshot pending = unsignedCompletion.requestRecovery(halted, request, NOW)
                .snapshot();
        OperationsSnapshot once = unsignedCompletion.approveRecovery(pending,
                approval(request, "alice", NOW, "a".repeat(64)), NOW).snapshot();
        OperationsSnapshot twice = unsignedCompletion.approveRecovery(once,
                approval(request, "bob", NOW, "b".repeat(64)), NOW).snapshot();

        assertHalted(unsignedCompletion.completeRecovery(twice,
                        completion(request, twice, "alice", NOW), NOW),
                OperationsReason.OPERATOR_VERIFICATION_FAILED);
    }

    @Test
    void watchdogHasNoExecutionSideEffectsOrTradingKernelDependency() {
        assertThat(DeterministicOperationsWatchdog.class.getDeclaredFields())
                .extracting(field -> field.getType().getName())
                .containsExactlyInAnyOrder(
                        OperationsWatchdogPolicy.class.getName(),
                        Clock.class.getName(),
                        DurableOperationsHeadPort.class.getName(),
                        OperationsObservationVerifier.class.getName(),
                        ManualTakeoverVerifier.class.getName(),
                        RecoveryApprovalVerifier.class.getName(),
                        RecoveryCompletionVerifier.class.getName(),
                        PersistedControlVerifier.class.getName());

        OperationsDecision decision = watchdog.tick(watchdog.initialState(), NOW);
        assertThat(decision.actionIntents()).isNotEmpty();
        assertThat(decision.snapshot().state()).isEqualTo(OperationsState.HALTED);
        assertThat(decision.actionIntents()).allSatisfy(intent -> {
            assertThat(intent.expectedPreviousSnapshotSha256())
                    .isEqualTo(decision.snapshot().previousSnapshotSha256());
            assertThat(intent.resultSnapshotSha256())
                    .isEqualTo(decision.snapshot().snapshotSha256());
            assertThat(intent.resultRevision()).isEqualTo(decision.snapshot().revision());
            assertThat(intent.subjectSha256()).isEqualTo(SUBJECT);
            assertThat(intent.policySha256()).isEqualTo(policy.policySha256());
            assertThat(intent.expiresAtMs() - intent.issuedAtMs())
                    .isEqualTo(policy.maximumActionIntentTtlMs());
            assertThat(intent.singleUseIdSha256()).hasSize(64);
            assertThat(intent.intentSha256()).hasSize(64);
        });

        OperationsHeadCasResult committed = watchdog.commit(decision);
        assertThat(committed.applied()).isTrue();
        assertThat(committed.resultingHeadSha256())
                .isEqualTo(decision.snapshot().snapshotSha256());
    }

    @Test
    void staleHeadAndUnverifiablePersistedControlFailClosed() {
        DeterministicOperationsWatchdog noHead = new DeterministicOperationsWatchdog(
                policy, clock, DurableOperationsHeadPort.failClosed(), this::verifyObservation,
                this::verifyTakeover, this::verifyApproval, this::verifyCompletion,
                persistedControlVerifier());
        assertHalted(noHead.tick(noHead.initialState(), NOW),
                OperationsReason.DURABLE_HEAD_NOT_CURRENT);

        OperationsSnapshot normal = normalState(NOW);
        ManualTakeoverCommand command = ManualTakeoverCommand.create(
                "unverifiable-lease", "operator-a", NOW - 1, NOW + 10_000, SUBJECT,
                normal.snapshotSha256(), "d".repeat(64));
        OperationsSnapshot manual = watchdog.enterManualTakeover(normal, command, NOW).snapshot();
        DeterministicOperationsWatchdog noReceiptTrust = new DeterministicOperationsWatchdog(
                policy, clock, headPort(), this::verifyObservation, this::verifyTakeover,
                this::verifyApproval, this::verifyCompletion, PersistedControlVerifier.failClosed());

        assertHalted(noReceiptTrust.tick(manual, NOW),
                OperationsReason.DURABLE_HEAD_NOT_CURRENT);
    }

    private OperationsDecision recover(OperationsSnapshot source,
                                       String requestId,
                                       RecoveryTarget target,
                                       long nowMs) {
        RecoveryRequest request = recoveryRequest(source, requestId, target, nowMs);
        OperationsSnapshot pending = watchdog.requestRecovery(source, request, nowMs).snapshot();
        OperationsSnapshot once = watchdog.approveRecovery(pending,
                approval(request, "alice-" + requestId, nowMs, "a".repeat(64)), nowMs).snapshot();
        OperationsSnapshot twice = watchdog.approveRecovery(once,
                approval(request, "bob-" + requestId, nowMs, "b".repeat(64)), nowMs).snapshot();
        return watchdog.completeRecovery(twice,
                completion(request, twice, "alice-" + requestId, nowMs), nowMs);
    }

    private RecoveryRequest recoveryRequest(OperationsSnapshot source,
                                            String requestId,
                                            RecoveryTarget target,
                                            long nowMs) {
        return RecoveryRequest.create(requestId, "recovery-coordinator", target,
                nowMs - 10, nowMs + 20_000, SUBJECT, source.snapshotSha256(), PURPOSE);
    }

    private RecoveryApproval approval(RecoveryRequest request,
                                      String operator,
                                      long nowMs,
                                      String attestation) {
        return RecoveryApproval.create(request, operator, nowMs - 5,
                nowMs + 10_000, attestation);
    }

    private RecoveryCompletionCommand completion(RecoveryRequest request,
                                                 OperationsSnapshot snapshot,
                                                 String operator,
                                                 long nowMs) {
        return RecoveryCompletionCommand.create(request, operator, nowMs - 1,
                nowMs + 5_000, snapshot.snapshotSha256(), "e".repeat(64));
    }

    private OperationsSnapshot normalState(long nowMs) {
        OperationsSnapshot halted = withAllHealthyFacts(watchdog.initialState(), nowMs);
        OperationsSnapshot degraded = recover(
                halted, "bootstrap-degraded", RecoveryTarget.DEGRADED, nowMs).snapshot();
        return recover(degraded, "bootstrap-normal", RecoveryTarget.NORMAL, nowMs).snapshot();
    }

    private OperationsSnapshot withAllHealthyFacts(OperationsSnapshot source, long nowMs) {
        return withAllHealthyFacts(watchdog, source, nowMs);
    }

    private OperationsSnapshot withAllHealthyFacts(DeterministicOperationsWatchdog target,
                                                    OperationsSnapshot source,
                                                    long nowMs) {
        OperationsSnapshot current = source;
        for (OperationsObservationKind kind : OperationsObservationKind.values()) {
            OperationsDecision decision = target.observe(current,
                    observation(kind, 1, OperationsHealth.HEALTHY, nowMs), nowMs);
            assertThat(decision.accepted()).as(decision.detail()).isTrue();
            current = decision.snapshot();
        }
        return current;
    }

    private OperationsObservation observation(OperationsObservationKind kind,
                                              long sequence,
                                              OperationsHealth health,
                                              long nowMs) {
        return OperationsObservation.create(kind, policy.authoritativeSources().get(kind), sequence,
                nowMs - 100, nowMs + 30_000, health, SUBJECT, PAYLOAD, "9".repeat(64));
    }

    private ManualTakeoverVerification verifyTakeover(ManualTakeoverCommand command, long nowMs) {
        return new ManualTakeoverVerification(true, VERIFIER, command.operatorId(),
                Set.of(OperationsWatchdogPolicy.MANUAL_TAKEOVER_ROLE), command.commandSha256(),
                command.subjectSha256(), policy.policySha256(),
                OperationsWatchdogPolicy.OPERATOR_AUDIENCE, command.basisSnapshotSha256(),
                command.attestationSha256(), "takeover-replay-" + command.commandId(), nowMs, "");
    }

    private OperationsObservationVerification verifyObservation(OperationsObservation observation,
                                                                long nowMs) {
        return new OperationsObservationVerification(true, OBSERVATION_VERIFIER,
                observation.source(), observation.observationSha256(), observation.subjectSha256(),
                observation.attestationSha256(), nowMs, "");
    }

    private RecoveryApprovalVerification verifyApproval(RecoveryApproval approval,
                                                        RecoveryRequest request,
                                                        long nowMs) {
        return new RecoveryApprovalVerification(true, VERIFIER, approval.operatorId(),
                Set.of(OperationsWatchdogPolicy.RECOVERY_APPROVER_ROLE), request.requestSha256(),
                approval.approvalSha256(), approval.subjectSha256(), policy.policySha256(),
                OperationsWatchdogPolicy.OPERATOR_AUDIENCE, approval.attestationSha256(),
                "approval-replay-" + approval.operatorId(), nowMs, "");
    }

    private RecoveryCompletionVerification verifyCompletion(RecoveryCompletionCommand command,
                                                            RecoveryRequest request,
                                                            long nowMs) {
        return new RecoveryCompletionVerification(true, VERIFIER, command.operatorId(),
                Set.of(OperationsWatchdogPolicy.RECOVERY_COMPLETER_ROLE), command.commandSha256(),
                request.requestSha256(), command.subjectSha256(), policy.policySha256(),
                OperationsWatchdogPolicy.OPERATOR_AUDIENCE, command.basisSnapshotSha256(),
                command.attestationSha256(), "completion-replay-" + command.commandSha256(),
                nowMs, "");
    }

    private DeterministicOperationsWatchdog watchdogWithClock(Clock targetClock) {
        return new DeterministicOperationsWatchdog(policy, targetClock, headPort(),
                this::verifyObservation, this::verifyTakeover, this::verifyApproval,
                this::verifyCompletion, persistedControlVerifier());
    }

    private DurableOperationsHeadPort headPort() {
        return new DurableOperationsHeadPort() {
            @Override
            public DurableHeadVerification verifyCurrent(OperationsSnapshot snapshot, long nowMs) {
                return new DurableHeadVerification(true, true, HEAD_AUTHORITY,
                        snapshot.subjectSha256(), snapshot.policySha256(),
                        snapshot.snapshotSha256(), snapshot.revision(), nowMs, "");
            }

            @Override
            public OperationsHeadCasResult compareAndSet(String expectedHeadSha256,
                                                         long expectedRevision,
                                                         OperationsSnapshot result,
                                                         java.util.List<OperationsActionIntent> intents) {
                return new OperationsHeadCasResult(true, HEAD_AUTHORITY,
                        result.snapshotSha256(), result.revision(), "");
            }
        };
    }

    private PersistedControlVerifier persistedControlVerifier() {
        return new PersistedControlVerifier() {
            @Override
            public PersistedControlVerification verifyManualTakeover(
                    ManualTakeoverLease lease, OperationsWatchdogPolicy ignored, long nowMs) {
                return new PersistedControlVerification(true, VERIFIER, lease.operatorId(),
                        OperationsWatchdogPolicy.MANUAL_TAKEOVER_ROLE, SUBJECT,
                        policy.policySha256(), lease.leaseSha256(), lease.attestationSha256(),
                        lease.replayReservationId(), nowMs, "");
            }

            @Override
            public PersistedControlVerification verifyRecoveryApproval(
                    RecoveryApprovalReceipt receipt,
                    RecoveryRequest request,
                    OperationsWatchdogPolicy ignored,
                    long nowMs) {
                return new PersistedControlVerification(true, VERIFIER, receipt.operatorId(),
                        OperationsWatchdogPolicy.RECOVERY_APPROVER_ROLE, SUBJECT,
                        policy.policySha256(), receipt.receiptSha256(), receipt.attestationSha256(),
                        receipt.replayReservationId(), nowMs, "");
            }
        };
    }

    private static OperationsWatchdogPolicy policy() {
        Map<OperationsObservationKind, String> sources =
                new EnumMap<>(OperationsObservationKind.class);
        sources.put(OperationsObservationKind.MARKET_DATA, "market-data-monitor");
        sources.put(OperationsObservationKind.OMS, "oms-monitor");
        sources.put(OperationsObservationKind.VENUE_TRUTH, "venue-truth-monitor");
        sources.put(OperationsObservationKind.WRITER_LEASE, "writer-lease-monitor");
        sources.put(OperationsObservationKind.KILL_SWITCH, "kill-switch-monitor");
        sources.put(OperationsObservationKind.GOVERNANCE_EVIDENCE, "governance-monitor");
        sources.put(OperationsObservationKind.AGENT_HEARTBEAT, "agent-runtime-monitor");
        return new OperationsWatchdogPolicy(SUBJECT, "f".repeat(64), sources,
                Set.of(HEAD_AUTHORITY),
                Set.of(OBSERVATION_VERIFIER),
                Set.of(VERIFIER),
                60_000, 60_000, 30_000, 5_000, 2);
    }

    private static void assertHalted(OperationsDecision decision, OperationsReason reason) {
        assertThat(decision.accepted()).isFalse();
        assertThat(decision.mustApply()).isTrue();
        assertThat(decision.reason()).isEqualTo(reason);
        assertThat(decision.snapshot().state()).isEqualTo(OperationsState.HALTED);
        assertThat(decision.actionIntents()).extracting(OperationsActionIntent::type)
                .contains(OperationsActionType.REQUEST_KILL_SWITCH_HALT,
                        OperationsActionType.HOLD_RISK_INCREASE);
    }

    private static final class MutableClock extends Clock {
        private long nowMs;

        private MutableClock(long nowMs) {
            this.nowMs = nowMs;
        }

        void setMillis(long nowMs) {
            this.nowMs = nowMs;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (ZoneOffset.UTC.equals(zone)) return this;
            throw new IllegalArgumentException("test clock is UTC only");
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(nowMs);
        }

        @Override
        public long millis() {
            return nowMs;
        }
    }

    private static final class FailingAfterReadsClock extends Clock {
        private final long nowMs;
        private final int successfulReads;
        private int reads;

        private FailingAfterReadsClock(long nowMs, int successfulReads) {
            this.nowMs = nowMs;
            this.successfulReads = successfulReads;
        }

        int readCount() {
            return reads;
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
            return Instant.ofEpochMilli(millis());
        }

        @Override
        public long millis() {
            if (reads++ >= successfulReads) throw new IllegalStateException("clock unavailable");
            return nowMs;
        }
    }
}
