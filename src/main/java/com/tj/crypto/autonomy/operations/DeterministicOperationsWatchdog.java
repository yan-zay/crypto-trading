package com.tj.crypto.autonomy.operations;

import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Independent deterministic watchdog, degradation and takeover state machine.
 *
 * <p>The class is deliberately not a Spring component and imports no execution, OMS, venue,
 * KillSwitch or Agent service. Every method returns immutable state plus declarative action intents.
 * A separately authorized integration layer may persist the decision and act on an intent.</p>
 *
 * <p>Safety invariants:</p>
 * <ul>
 *   <li>startup and every malformed/unknown/expired/out-of-order/conflicting input are HALTED;</li>
 *   <li>health observations can tighten state but can never relax it;</li>
 *   <li>HALTED/MANUAL_TAKEOVER can recover only to DEGRADED, and DEGRADED requires a new
 *       dual-control recovery request before NORMAL;</li>
 *   <li>Agent heartbeat can degrade or halt the system but can never complete recovery.</li>
 * </ul>
 */
public final class DeterministicOperationsWatchdog {
    private final OperationsWatchdogPolicy policy;
    private final Clock trustedClock;
    private final DurableOperationsHeadPort durableHeadPort;
    private final OperationsObservationVerifier observationVerifier;
    private final ManualTakeoverVerifier manualTakeoverVerifier;
    private final RecoveryApprovalVerifier recoveryApprovalVerifier;
    private final RecoveryCompletionVerifier recoveryCompletionVerifier;
    private final PersistedControlVerifier persistedControlVerifier;

    public DeterministicOperationsWatchdog(OperationsWatchdogPolicy policy,
                                           Clock trustedClock,
                                           DurableOperationsHeadPort durableHeadPort,
                                           OperationsObservationVerifier observationVerifier,
                                           ManualTakeoverVerifier manualTakeoverVerifier,
                                           RecoveryApprovalVerifier recoveryApprovalVerifier,
                                           RecoveryCompletionVerifier recoveryCompletionVerifier,
                                           PersistedControlVerifier persistedControlVerifier) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.trustedClock = Objects.requireNonNull(trustedClock, "trustedClock");
        this.durableHeadPort = Objects.requireNonNull(durableHeadPort, "durableHeadPort");
        this.observationVerifier = Objects.requireNonNull(
                observationVerifier, "observationVerifier");
        this.manualTakeoverVerifier = Objects.requireNonNull(
                manualTakeoverVerifier, "manualTakeoverVerifier");
        this.recoveryApprovalVerifier = Objects.requireNonNull(
                recoveryApprovalVerifier, "recoveryApprovalVerifier");
        this.recoveryCompletionVerifier = Objects.requireNonNull(
                recoveryCompletionVerifier, "recoveryCompletionVerifier");
        this.persistedControlVerifier = Objects.requireNonNull(
                persistedControlVerifier, "persistedControlVerifier");
    }

    public OperationsSnapshot initialState() {
        return OperationsSnapshot.failClosedInitial(
                policy.subjectSha256(), policy.policySha256(), authoritativeNowOrFailClosed());
    }

    /** Atomically persists a previously calculated transition; it never executes an action intent. */
    public OperationsHeadCasResult commit(OperationsDecision decision) {
        if (decision == null || !decision.mustApply() || decision.snapshot() == null
                || decision.actionIntents().isEmpty()) {
            return OperationsHeadCasResult.notApplied("an applicable watchdog decision is required");
        }
        OperationsSnapshot result = decision.snapshot();
        long commitNowMs;
        try {
            commitNowMs = trustedClock.millis();
        } catch (RuntimeException timeFailure) {
            return OperationsHeadCasResult.notApplied("trusted time is unavailable at CAS");
        }
        boolean intentsBound = decision.actionIntents().stream().allMatch(intent ->
                intent.subjectSha256().equals(result.subjectSha256())
                        && intent.policySha256().equals(result.policySha256())
                        && intent.expectedPreviousSnapshotSha256()
                        .equals(result.previousSnapshotSha256())
                        && intent.resultSnapshotSha256().equals(result.snapshotSha256())
                        && intent.resultRevision() == result.revision()
                        && intent.issuedAtMs() <= commitNowMs
                        && intent.expiresAtMs() > commitNowMs);
        if (!intentsBound) {
            return OperationsHeadCasResult.notApplied("action intent lineage mismatch");
        }
        OperationsHeadCasResult applied;
        try {
            long expectedRevision = result.revision() == 0 ? -1 : result.revision() - 1;
            applied = durableHeadPort.compareAndSet(result.previousSnapshotSha256(),
                    expectedRevision, result, decision.actionIntents());
        } catch (RuntimeException storageFailure) {
            return OperationsHeadCasResult.notApplied("durable head CAS failed closed");
        }
        if (applied == null || !applied.applied()
                || !policy.trustedHeadAuthorityIds().contains(applied.authorityId())
                || !result.snapshotSha256().equals(applied.resultingHeadSha256())
                || result.revision() != applied.resultingRevision()
                || !applied.blocker().isBlank()) {
            return OperationsHeadCasResult.notApplied("durable head CAS was not verified");
        }
        return applied;
    }

    public OperationsDecision observe(OperationsSnapshot current,
                                      OperationsObservation input,
                                      long nowMs) {
        TimeCheck time = checkTime(current, nowMs);
        if (!time.valid()) return time.failure();
        nowMs = time.trustedNowMs();
        SnapshotCheck checked = checkSnapshot(current, nowMs);
        if (!checked.valid()) return checked.failure();
        OperationsSnapshot snapshot = checked.snapshot();
        ObservationCheck validation = validateObservation(input, nowMs);
        if (!validation.valid()) {
            return halt(snapshot, validation.reason(), validation.detail(), nowMs);
        }
        if (!trustedObservation(input, nowMs)) {
            return halt(snapshot, OperationsReason.INPUT_SOURCE_MISMATCH,
                    "observation source attestation could not be independently verified", nowMs);
        }
        OperationsObservationKind kind = validation.kind();
        OperationsObservation previous = snapshot.latestObservations().get(kind);
        if (previous == null && input.sequence() != 1) {
            return halt(snapshot, OperationsReason.INPUT_SEQUENCE_GAP,
                    "first observation sequence must be 1 for " + kind, nowMs);
        }
        if (previous != null) {
            if (input.sequence() < previous.sequence()) {
                return halt(snapshot, OperationsReason.INPUT_OUT_OF_ORDER,
                        "observation sequence regressed for " + kind, nowMs);
            }
            if (input.sequence() == previous.sequence()) {
                if (!input.observationSha256().equals(previous.observationSha256())) {
                    return halt(snapshot, OperationsReason.INPUT_CONFLICT,
                            "same sequence carries different content for " + kind, nowMs);
                }
                OperationsDecision freshness = evaluateCurrentFacts(snapshot, nowMs);
                if (freshness != null) return freshness;
                return noOpDecision(true, OperationsReason.IDEMPOTENT_REPLAY,
                        "identical observation replay", snapshot);
            }
            if (input.sequence() != previous.sequence() + 1) {
                return halt(snapshot, OperationsReason.INPUT_SEQUENCE_GAP,
                        "observation sequence gap for " + kind, nowMs);
            }
        }

        EnumMap<OperationsObservationKind, OperationsObservation> observations =
                new EnumMap<>(OperationsObservationKind.class);
        observations.putAll(snapshot.latestObservations());
        observations.put(kind, input);

        if (input.health() == OperationsHealth.UNKNOWN) {
            return halt(snapshot, observations, OperationsReason.INPUT_UNKNOWN,
                    "authoritative source reported UNKNOWN for " + kind, nowMs);
        }
        if (input.health() == OperationsHealth.FAILED) {
            return halt(snapshot, observations, OperationsReason.HEALTH_FAILED,
                    "authoritative source reported FAILED for " + kind, nowMs);
        }
        if (input.health() == OperationsHealth.DEGRADED) {
            if (snapshot.state() == OperationsState.RECOVERY_PENDING) {
                return halt(snapshot, observations, OperationsReason.HEALTH_DEGRADED,
                        "recovery invalidated by degraded " + kind, nowMs);
            }
            OperationsState target = switch (snapshot.state()) {
                case NORMAL, DEGRADED -> OperationsState.DEGRADED;
                case HALTED -> OperationsState.HALTED;
                case MANUAL_TAKEOVER -> OperationsState.MANUAL_TAKEOVER;
                case RECOVERY_PENDING -> throw new IllegalStateException("handled above");
            };
            OperationsSnapshot next = next(snapshot, target, observations, null, List.of(),
                    OperationsReason.HEALTH_DEGRADED, nowMs);
            return decision(true, OperationsReason.HEALTH_DEGRADED,
                    "degraded observation accepted for " + kind, next);
        }

        OperationsSnapshot next = next(snapshot, snapshot.state(), observations,
                snapshot.pendingRecovery(), snapshot.recoveryApprovals(),
                OperationsReason.OBSERVATION_ACCEPTED, nowMs);
        OperationsDecision freshness = evaluateCurrentFacts(next, nowMs);
        return freshness == null
                ? decision(true, OperationsReason.OBSERVATION_ACCEPTED,
                "healthy observation accepted for " + kind, next)
                : freshness;
    }

    /** Deterministic time advance used to detect expiration even when no producer emits a new fact. */
    public OperationsDecision tick(OperationsSnapshot current, long nowMs) {
        TimeCheck time = checkTime(current, nowMs);
        if (!time.valid()) return time.failure();
        nowMs = time.trustedNowMs();
        SnapshotCheck checked = checkSnapshot(current, nowMs);
        if (!checked.valid()) return checked.failure();
        OperationsDecision freshness = evaluateCurrentFacts(checked.snapshot(), nowMs);
        if (freshness != null) return freshness;
        OperationsSnapshot snapshot = checked.snapshot();
        OperationsSnapshot evaluated = next(snapshot, snapshot.state(),
                snapshot.latestObservations(), snapshot.pendingRecovery(),
                snapshot.recoveryApprovals(), OperationsReason.OBSERVATION_ACCEPTED, nowMs);
        return decision(true, OperationsReason.OBSERVATION_ACCEPTED,
                "watchdog facts remain current", evaluated);
    }

    public OperationsDecision enterManualTakeover(OperationsSnapshot current,
                                                  ManualTakeoverCommand command,
                                                  long nowMs) {
        TimeCheck time = checkTime(current, nowMs);
        if (!time.valid()) return time.failure();
        nowMs = time.trustedNowMs();
        SnapshotCheck checked = checkSnapshot(current, nowMs);
        if (!checked.valid()) return checked.failure();
        OperationsSnapshot snapshot = checked.snapshot();
        if (command != null && command.hasValidDigest()
                && command.commandSha256().equals(
                snapshot.lastConsumedControlCommandSha256())) {
            return noOpDecision(true, OperationsReason.IDEMPOTENT_REPLAY,
                    "manual takeover command was already consumed", snapshot);
        }
        if (!validManualCommand(command, snapshot, nowMs)) {
            return halt(snapshot, OperationsReason.INPUT_MALFORMED,
                    "manual takeover command is malformed, stale or bound to another snapshot",
                    nowMs);
        }
        ManualTakeoverVerification verification;
        try {
            verification = manualTakeoverVerifier.verify(command, nowMs);
        } catch (RuntimeException verifierFailure) {
            return halt(snapshot, OperationsReason.OPERATOR_VERIFICATION_FAILED,
                    "manual takeover verifier failed closed", nowMs);
        }
        if (!trustedManualVerification(command, verification, nowMs)) {
            return halt(snapshot, OperationsReason.OPERATOR_VERIFICATION_FAILED,
                    "manual takeover operator could not be independently verified", nowMs);
        }
        ManualTakeoverLease lease = ManualTakeoverLease.create(command, verification);
        OperationsSnapshot next = OperationsSnapshot.create(snapshot.revision() + 1,
                nowMs,
                OperationsState.MANUAL_TAKEOVER, policy.subjectSha256(), policy.policySha256(),
                snapshot.snapshotSha256(), command.commandSha256(), snapshot.latestObservations(),
                lease, null, null, List.of(), OperationsReason.MANUAL_TAKEOVER_ENTERED);
        return decision(true, OperationsReason.MANUAL_TAKEOVER_ENTERED,
                "manual control assigned to authenticated operator " + command.operatorId(), next);
    }

    public OperationsDecision requestRecovery(OperationsSnapshot current,
                                              RecoveryRequest request,
                                              long nowMs) {
        TimeCheck time = checkTime(current, nowMs);
        if (!time.valid()) return time.failure();
        nowMs = time.trustedNowMs();
        SnapshotCheck checked = checkSnapshot(current, nowMs);
        if (!checked.valid()) return checked.failure();
        OperationsSnapshot snapshot = checked.snapshot();
        if (snapshot.state() == OperationsState.RECOVERY_PENDING) {
            if (snapshot.pendingRecovery() == null
                    || nowMs >= snapshot.pendingRecovery().expiresAtMs()) {
                return halt(snapshot, OperationsReason.INPUT_EXPIRED,
                        "pending recovery request expired", nowMs);
            }
            if (request != null && snapshot.pendingRecovery() != null
                    && Objects.equals(request.requestSha256(),
                    snapshot.pendingRecovery().requestSha256())) {
                return noOpDecision(true, OperationsReason.IDEMPOTENT_REPLAY,
                        "identical recovery request replay", snapshot);
            }
            return halt(snapshot, OperationsReason.INPUT_CONFLICT,
                    "a different recovery request is already pending", nowMs);
        }
        if (!validRecoveryRequest(request, snapshot, nowMs)) {
            return halt(snapshot, OperationsReason.RECOVERY_REQUEST_INVALID,
                    "recovery request is malformed, stale or bound to another snapshot", nowMs);
        }
        boolean allowedTarget = switch (snapshot.state()) {
            case HALTED, MANUAL_TAKEOVER -> request.target() == RecoveryTarget.DEGRADED;
            case DEGRADED -> request.target() == RecoveryTarget.NORMAL;
            case NORMAL, RECOVERY_PENDING -> false;
        };
        if (!allowedTarget) {
            return halt(snapshot, OperationsReason.RECOVERY_REQUEST_INVALID,
                    "recovery target would skip a mandatory step or relax NORMAL", nowMs);
        }
        FactProblem factProblem = firstFactProblem(snapshot, nowMs);
        if (factProblem != null) {
            return halt(snapshot, factProblem.reason(),
                    "recovery requires all authoritative facts HEALTHY: " + factProblem.detail(),
                    nowMs);
        }
        OperationsSnapshot next = next(snapshot, OperationsState.RECOVERY_PENDING,
                snapshot.latestObservations(), request, List.of(),
                OperationsReason.RECOVERY_REQUESTED, nowMs);
        return decision(true, OperationsReason.RECOVERY_REQUESTED,
                "recovery is awaiting independent dual control", next);
    }

    public OperationsDecision approveRecovery(OperationsSnapshot current,
                                              RecoveryApproval approval,
                                              long nowMs) {
        TimeCheck time = checkTime(current, nowMs);
        if (!time.valid()) return time.failure();
        nowMs = time.trustedNowMs();
        SnapshotCheck checked = checkSnapshot(current, nowMs);
        if (!checked.valid()) return checked.failure();
        OperationsSnapshot snapshot = checked.snapshot();
        RecoveryRequest request = snapshot.pendingRecovery();
        if (snapshot.state() != OperationsState.RECOVERY_PENDING || request == null
                || nowMs < request.requestedAtMs() || nowMs >= request.expiresAtMs()) {
            return halt(snapshot, OperationsReason.RECOVERY_APPROVAL_INVALID,
                    "no active recovery request exists", nowMs);
        }
        if (!validRecoveryApproval(approval, request, nowMs)) {
            return halt(snapshot, OperationsReason.RECOVERY_APPROVAL_INVALID,
                    "recovery approval is malformed, stale or bound to another request", nowMs);
        }
        if (snapshot.recoveryApprovals().stream().anyMatch(existing ->
                existing.approvalSha256().equals(approval.approvalSha256()))) {
            return noOpDecision(true, OperationsReason.IDEMPOTENT_REPLAY,
                    "identical recovery approval replay", snapshot);
        }
        RecoveryApprovalVerification verification;
        try {
            verification = recoveryApprovalVerifier.verify(approval, request, nowMs);
        } catch (RuntimeException verifierFailure) {
            return halt(snapshot, OperationsReason.RECOVERY_APPROVAL_INVALID,
                    "recovery approval verifier failed closed", nowMs);
        }
        if (!trustedRecoveryVerification(approval, request, verification, nowMs)) {
            return halt(snapshot, OperationsReason.RECOVERY_APPROVAL_INVALID,
                    "recovery approval could not be independently verified", nowMs);
        }

        for (RecoveryApprovalReceipt existing : snapshot.recoveryApprovals()) {
            boolean sameOperator = existing.operatorId().equalsIgnoreCase(
                    verification.verifiedOperatorId());
            boolean sameReplay = existing.replayReservationId().equals(
                    verification.replayReservationId());
            boolean sameAttestation = existing.attestationSha256().equals(
                    verification.recomputedAttestationSha256());
            if (sameOperator || sameReplay || sameAttestation) {
                return halt(snapshot, OperationsReason.RECOVERY_APPROVAL_CONFLICT,
                        "dual control contains a duplicate operator or replay artifact", nowMs);
            }
        }

        List<RecoveryApprovalReceipt> receipts = new ArrayList<>(snapshot.recoveryApprovals());
        receipts.add(RecoveryApprovalReceipt.create(approval, verification));
        OperationsReason reason = receipts.size() >= policy.requiredRecoveryApprovers()
                ? OperationsReason.RECOVERY_CONFIRMATION_REQUIRED
                : OperationsReason.RECOVERY_DUAL_CONTROL_PENDING;
        OperationsSnapshot next = next(snapshot, OperationsState.RECOVERY_PENDING,
                snapshot.latestObservations(), request, receipts, reason, nowMs);
        return decision(true, reason,
                receipts.size() >= policy.requiredRecoveryApprovers()
                        ? "dual control satisfied; explicit recovery confirmation is still required"
                        : "another independent recovery approver is required", next);
    }

    /**
     * Explicit confirmation after dual control. This method never permits HALTED directly to NORMAL.
     * A second request and a second independent dual-control round are required from DEGRADED.
     */
    public OperationsDecision completeRecovery(OperationsSnapshot current,
                                               RecoveryCompletionCommand command,
                                               long nowMs) {
        TimeCheck time = checkTime(current, nowMs);
        if (!time.valid()) return time.failure();
        nowMs = time.trustedNowMs();
        SnapshotCheck checked = checkSnapshot(current, nowMs);
        if (!checked.valid()) return checked.failure();
        OperationsSnapshot snapshot = checked.snapshot();
        if (command != null && command.hasValidDigest()
                && command.commandSha256().equals(
                snapshot.lastConsumedControlCommandSha256())) {
            return noOpDecision(true, OperationsReason.IDEMPOTENT_REPLAY,
                    "recovery completion command was already consumed", snapshot);
        }
        RecoveryRequest request = snapshot.pendingRecovery();
        if (snapshot.state() != OperationsState.RECOVERY_PENDING || request == null
                || !validRecoveryCompletion(command, request, snapshot, nowMs)
                || nowMs < request.requestedAtMs() || nowMs >= request.expiresAtMs()) {
            return halt(snapshot, OperationsReason.RECOVERY_REQUEST_INVALID,
                    "recovery confirmation is missing, stale or bound to another request", nowMs);
        }
        if (!hasIndependentCurrentApprovals(
                snapshot.recoveryApprovals(), request.requestSha256(), nowMs)) {
            return noOpDecision(false, OperationsReason.RECOVERY_DUAL_CONTROL_PENDING,
                    "independent dual control is incomplete or expired", snapshot);
        }
        FactProblem factProblem = firstFactProblem(snapshot, nowMs);
        if (factProblem != null) {
            return halt(snapshot, factProblem.reason(),
                    "recovery facts are no longer healthy: " + factProblem.detail(), nowMs);
        }
        RecoveryCompletionVerification completionVerification;
        try {
            completionVerification = recoveryCompletionVerifier.verify(command, request, nowMs);
        } catch (RuntimeException verifierFailure) {
            return halt(snapshot, OperationsReason.OPERATOR_VERIFICATION_FAILED,
                    "recovery completion verifier failed closed", nowMs);
        }
        if (!trustedRecoveryCompletion(command, request, snapshot, completionVerification, nowMs)) {
            return halt(snapshot, OperationsReason.OPERATOR_VERIFICATION_FAILED,
                    "recovery completion was not signed by an approved human operator", nowMs);
        }
        OperationsState target = request.target() == RecoveryTarget.DEGRADED
                ? OperationsState.DEGRADED : OperationsState.NORMAL;
        OperationsReason reason = target == OperationsState.DEGRADED
                ? OperationsReason.RECOVERY_COMPLETED_TO_DEGRADED
                : OperationsReason.RECOVERY_COMPLETED_TO_NORMAL;
        OperationsSnapshot next = next(snapshot, target, snapshot.latestObservations(), null,
                List.of(), reason, nowMs, command.commandSha256());
        return decision(true, reason,
                target == OperationsState.DEGRADED
                        ? "recovered only to DEGRADED; NORMAL needs a new dual-control request"
                        : "explicit stepwise dual-control recovery completed", next);
    }

    private ObservationCheck validateObservation(OperationsObservation input, long nowMs) {
        if (input == null) {
            return ObservationCheck.invalid(OperationsReason.INPUT_MALFORMED,
                    "observation is required");
        }
        OperationsObservationKind kind = OperationsObservationKind.parse(input.inputType())
                .orElse(null);
        if (kind == null || !kind.name().equals(input.inputType())) {
            return ObservationCheck.invalid(OperationsReason.INPUT_UNKNOWN,
                    "observation type is unknown or non-canonical");
        }
        if (!OperationsHashing.safeText(input.source(), 128)
                || input.health() == null || input.sequence() <= 0
                || input.observedAtMs() <= 0 || input.expiresAtMs() <= input.observedAtMs()
                || input.expiresAtMs() - input.observedAtMs() > policy.maximumObservationTtlMs()) {
            return ObservationCheck.invalid(OperationsReason.INPUT_MALFORMED,
                    "observation metadata or TTL is invalid");
        }
        if (input.observedAtMs() > nowMs) {
            return ObservationCheck.invalid(OperationsReason.INPUT_MALFORMED,
                    "future observations are not accepted");
        }
        if (nowMs >= input.expiresAtMs()) {
            return ObservationCheck.invalid(OperationsReason.INPUT_EXPIRED,
                    "observation is expired");
        }
        if (!policy.authoritativeSources().get(kind).equals(input.source())) {
            return ObservationCheck.invalid(OperationsReason.INPUT_SOURCE_MISMATCH,
                    "observation came from a non-authoritative source");
        }
        if (!policy.subjectSha256().equals(input.subjectSha256())) {
            return ObservationCheck.invalid(OperationsReason.INPUT_SUBJECT_MISMATCH,
                    "observation is bound to another governance subject");
        }
        if (!OperationsHashing.isSha256(input.payloadSha256())
                || !OperationsHashing.isSha256(input.attestationSha256())
                || !input.hasValidDigest()) {
            return ObservationCheck.invalid(OperationsReason.INPUT_DIGEST_MISMATCH,
                    "observation content digest is invalid");
        }
        return ObservationCheck.valid(kind);
    }

    private boolean trustedObservation(OperationsObservation observation, long nowMs) {
        OperationsObservationVerification verification;
        try {
            verification = observationVerifier.verify(observation, nowMs);
        } catch (RuntimeException verifierFailure) {
            return false;
        }
        return verification != null && verification.trusted()
                && policy.trustedObservationVerifierIds().contains(verification.verifierId())
                && observation.source().equals(verification.verifiedSource())
                && observation.observationSha256().equals(
                verification.verifiedObservationSha256())
                && observation.subjectSha256().equals(verification.verifiedSubjectSha256())
                && observation.attestationSha256().equals(
                verification.recomputedAttestationSha256())
                && verification.verifiedAtMs() >= observation.observedAtMs()
                && verification.verifiedAtMs() <= nowMs
                && verification.blocker().isBlank();
    }

    private OperationsDecision evaluateCurrentFacts(OperationsSnapshot snapshot, long nowMs) {
        if (snapshot.state() == OperationsState.MANUAL_TAKEOVER
                && (snapshot.manualTakeoverLease() == null
                || nowMs >= snapshot.manualTakeoverLease().expiresAtMs())) {
            return halt(snapshot, OperationsReason.INPUT_EXPIRED,
                    "manual takeover control lease expired", nowMs);
        }
        if (snapshot.state() == OperationsState.RECOVERY_PENDING
                && (snapshot.pendingRecovery() == null
                || nowMs >= snapshot.pendingRecovery().expiresAtMs()
                || snapshot.recoveryApprovals().stream()
                .anyMatch(approval -> nowMs >= approval.expiresAtMs()))) {
            return halt(snapshot, OperationsReason.INPUT_EXPIRED,
                    "recovery request or approval expired", nowMs);
        }
        FactProblem expired = firstExpiredFact(snapshot, nowMs);
        if (expired != null) return halt(snapshot, expired.reason(), expired.detail(), nowMs);
        if (snapshot.state() == OperationsState.HALTED
                || snapshot.state() == OperationsState.MANUAL_TAKEOVER) return null;

        FactProblem problem = firstFactProblem(snapshot, nowMs);
        if (problem == null) return null;
        if (problem.health() == OperationsHealth.DEGRADED
                && snapshot.state() != OperationsState.RECOVERY_PENDING) {
            OperationsSnapshot next = next(snapshot, OperationsState.DEGRADED,
                    snapshot.latestObservations(), null, List.of(),
                    OperationsReason.HEALTH_DEGRADED, nowMs);
            return decision(true, OperationsReason.HEALTH_DEGRADED, problem.detail(), next);
        }
        return halt(snapshot, problem.reason(), problem.detail(), nowMs);
    }

    private FactProblem firstExpiredFact(OperationsSnapshot snapshot, long nowMs) {
        for (OperationsObservationKind kind : OperationsObservationKind.values()) {
            OperationsObservation observation = snapshot.latestObservations().get(kind);
            if (observation != null && nowMs >= observation.expiresAtMs()) {
                return new FactProblem(OperationsReason.REQUIRED_FACT_EXPIRED,
                        kind + " observation expired", null);
            }
        }
        return null;
    }

    private FactProblem firstFactProblem(OperationsSnapshot snapshot, long nowMs) {
        for (OperationsObservationKind kind : OperationsObservationKind.values()) {
            OperationsObservation observation = snapshot.latestObservations().get(kind);
            if (observation == null) {
                return new FactProblem(OperationsReason.REQUIRED_FACT_MISSING,
                        kind + " observation is missing", null);
            }
            if (nowMs < observation.observedAtMs() || nowMs >= observation.expiresAtMs()) {
                return new FactProblem(OperationsReason.REQUIRED_FACT_EXPIRED,
                        kind + " observation is not current", null);
            }
            if (observation.health() == OperationsHealth.FAILED
                    || observation.health() == OperationsHealth.UNKNOWN
                    || observation.health() == null) {
                return new FactProblem(OperationsReason.HEALTH_FAILED,
                        kind + " is not a known safe state", observation.health());
            }
            if (observation.health() == OperationsHealth.DEGRADED) {
                return new FactProblem(OperationsReason.HEALTH_DEGRADED,
                        kind + " is degraded", observation.health());
            }
        }
        return null;
    }

    private boolean validManualCommand(ManualTakeoverCommand command,
                                       OperationsSnapshot snapshot,
                                       long nowMs) {
        return command != null
                && OperationsHashing.safeText(command.commandId(), 128)
                && OperationsHashing.safeText(command.operatorId(), 128)
                && command.issuedAtMs() > 0 && command.issuedAtMs() <= nowMs
                && command.expiresAtMs() > nowMs
                && command.expiresAtMs() - command.issuedAtMs()
                <= policy.maximumApprovalTtlMs()
                && policy.subjectSha256().equals(command.subjectSha256())
                && snapshot.snapshotSha256().equals(command.basisSnapshotSha256())
                && OperationsHashing.isSha256(command.attestationSha256())
                && command.hasValidDigest();
    }

    private boolean trustedManualVerification(ManualTakeoverCommand command,
                                              ManualTakeoverVerification verification,
                                              long nowMs) {
        return verification != null && verification.trusted()
                && policy.trustedOperatorVerifierIds().contains(verification.verifierId())
                && command.operatorId().equals(verification.verifiedOperatorId())
                && verification.verifiedRoles().contains(OperationsWatchdogPolicy.MANUAL_TAKEOVER_ROLE)
                && command.commandSha256().equals(verification.verifiedCommandSha256())
                && command.subjectSha256().equals(verification.verifiedSubjectSha256())
                && policy.policySha256().equals(verification.verifiedPolicySha256())
                && OperationsWatchdogPolicy.OPERATOR_AUDIENCE.equals(
                verification.verifiedAudience())
                && command.basisSnapshotSha256().equals(
                verification.verifiedBasisSnapshotSha256())
                && command.attestationSha256().equals(
                verification.recomputedAttestationSha256())
                && OperationsHashing.safeText(verification.replayReservationId(), 128)
                && verification.verifiedAtMs() >= command.issuedAtMs()
                && verification.verifiedAtMs() <= nowMs
                && verification.blocker().isBlank();
    }

    private boolean validRecoveryRequest(RecoveryRequest request,
                                         OperationsSnapshot snapshot,
                                         long nowMs) {
        return request != null && request.target() != null
                && OperationsHashing.safeText(request.requestId(), 128)
                && OperationsHashing.safeText(request.requestedBy(), 128)
                && request.requestedAtMs() > 0 && request.requestedAtMs() <= nowMs
                && request.expiresAtMs() > nowMs
                && request.expiresAtMs() - request.requestedAtMs()
                <= policy.maximumRecoveryTtlMs()
                && policy.subjectSha256().equals(request.subjectSha256())
                && snapshot.snapshotSha256().equals(request.basisSnapshotSha256())
                && OperationsHashing.isSha256(request.purposeSha256())
                && request.hasValidDigest();
    }

    private boolean validRecoveryApproval(RecoveryApproval approval,
                                          RecoveryRequest request,
                                          long nowMs) {
        return approval != null
                && OperationsHashing.safeText(approval.operatorId(), 128)
                && request.requestId().equals(approval.requestId())
                && request.requestSha256().equals(approval.requestSha256())
                && policy.subjectSha256().equals(approval.subjectSha256())
                && approval.approvedAtMs() >= request.requestedAtMs()
                && approval.approvedAtMs() <= nowMs
                && approval.expiresAtMs() > nowMs
                && approval.expiresAtMs() <= request.expiresAtMs()
                && approval.expiresAtMs() - approval.approvedAtMs()
                <= policy.maximumApprovalTtlMs()
                && OperationsHashing.isSha256(approval.attestationSha256())
                && approval.hasValidDigest();
    }

    private boolean trustedRecoveryVerification(RecoveryApproval approval,
                                                RecoveryRequest request,
                                                RecoveryApprovalVerification verification,
                                                long nowMs) {
        return verification != null && verification.trusted()
                && policy.trustedOperatorVerifierIds().contains(verification.verifierId())
                && approval.operatorId().equals(verification.verifiedOperatorId())
                && !approval.operatorId().equalsIgnoreCase(request.requestedBy())
                && verification.verifiedRoles().contains(
                OperationsWatchdogPolicy.RECOVERY_APPROVER_ROLE)
                && request.requestSha256().equals(verification.verifiedRequestSha256())
                && approval.approvalSha256().equals(verification.verifiedApprovalSha256())
                && policy.subjectSha256().equals(verification.verifiedSubjectSha256())
                && policy.policySha256().equals(verification.verifiedPolicySha256())
                && OperationsWatchdogPolicy.OPERATOR_AUDIENCE.equals(
                verification.verifiedAudience())
                && approval.attestationSha256().equals(
                verification.recomputedAttestationSha256())
                && OperationsHashing.safeText(verification.replayReservationId(), 128)
                && verification.verifiedAtMs() >= approval.approvedAtMs()
                && verification.verifiedAtMs() <= nowMs
                && verification.blocker().isBlank();
    }

    private boolean validRecoveryCompletion(RecoveryCompletionCommand command,
                                            RecoveryRequest request,
                                            OperationsSnapshot snapshot,
                                            long nowMs) {
        return command != null
                && OperationsHashing.safeText(command.operatorId(), 128)
                && request.requestId().equals(command.requestId())
                && request.requestSha256().equals(command.requestSha256())
                && policy.subjectSha256().equals(command.subjectSha256())
                && snapshot.snapshotSha256().equals(command.basisSnapshotSha256())
                && command.issuedAtMs() > 0 && command.issuedAtMs() <= nowMs
                && command.expiresAtMs() > nowMs
                && command.expiresAtMs() <= request.expiresAtMs()
                && command.expiresAtMs() - command.issuedAtMs()
                <= policy.maximumApprovalTtlMs()
                && OperationsHashing.isSha256(command.attestationSha256())
                && command.hasValidDigest();
    }

    private boolean trustedRecoveryCompletion(RecoveryCompletionCommand command,
                                              RecoveryRequest request,
                                              OperationsSnapshot snapshot,
                                              RecoveryCompletionVerification verification,
                                              long nowMs) {
        boolean approvedOperator = snapshot.recoveryApprovals().stream()
                .anyMatch(approval -> approval.operatorId().equalsIgnoreCase(command.operatorId()));
        return approvedOperator && verification != null && verification.trusted()
                && policy.trustedOperatorVerifierIds().contains(verification.verifierId())
                && command.operatorId().equals(verification.verifiedOperatorId())
                && verification.verifiedRoles().contains(
                OperationsWatchdogPolicy.RECOVERY_COMPLETER_ROLE)
                && command.commandSha256().equals(verification.verifiedCommandSha256())
                && request.requestSha256().equals(verification.verifiedRequestSha256())
                && policy.subjectSha256().equals(verification.verifiedSubjectSha256())
                && policy.policySha256().equals(verification.verifiedPolicySha256())
                && OperationsWatchdogPolicy.OPERATOR_AUDIENCE.equals(
                verification.verifiedAudience())
                && snapshot.snapshotSha256().equals(
                verification.verifiedBasisSnapshotSha256())
                && command.attestationSha256().equals(
                verification.recomputedAttestationSha256())
                && OperationsHashing.safeText(verification.replayReservationId(), 128)
                && verification.verifiedAtMs() >= command.issuedAtMs()
                && verification.verifiedAtMs() <= nowMs
                && verification.blocker().isBlank();
    }

    private boolean hasIndependentCurrentApprovals(List<RecoveryApprovalReceipt> approvals,
                                                   String requestSha256,
                                                   long nowMs) {
        if (approvals.size() < policy.requiredRecoveryApprovers()) return false;
        Set<String> operators = new HashSet<>();
        Set<String> replays = new HashSet<>();
        Set<String> attestations = new HashSet<>();
        for (RecoveryApprovalReceipt approval : approvals) {
            if (!approval.hasValidDigest()
                    || !requestSha256.equals(approval.requestSha256())
                    || nowMs >= approval.expiresAtMs()
                    || !policy.trustedOperatorVerifierIds().contains(approval.verifierId())
                    || !operators.add(approval.operatorId().toLowerCase(Locale.ROOT))
                    || !replays.add(approval.replayReservationId())
                    || !attestations.add(approval.attestationSha256())) return false;
        }
        return operators.size() >= policy.requiredRecoveryApprovers();
    }

    private SnapshotCheck checkSnapshot(OperationsSnapshot snapshot, long nowMs) {
        if (snapshot != null && policy.subjectSha256().equals(snapshot.subjectSha256())
                && policy.policySha256().equals(snapshot.policySha256())
                && snapshot.hasValidDigest() && validRestoredSnapshot(snapshot, nowMs)) {
            DurableHeadVerification head;
            try {
                head = durableHeadPort.verifyCurrent(snapshot, nowMs);
            } catch (RuntimeException storageFailure) {
                head = DurableHeadVerification.untrusted("durable head query failed");
            }
            if (trustedCurrentHead(snapshot, head, nowMs)) return SnapshotCheck.valid(snapshot);
        }
        OperationsSnapshot failed = OperationsSnapshot.failClosedInitial(
                policy.subjectSha256(), policy.policySha256(), Math.max(1, nowMs));
        return SnapshotCheck.invalid(decision(false, OperationsReason.DURABLE_HEAD_NOT_CURRENT,
                "snapshot is absent, corrupt, stale or not the verified durable current head",
                failed));
    }

    private boolean trustedCurrentHead(OperationsSnapshot snapshot,
                                       DurableHeadVerification head,
                                       long nowMs) {
        return head != null && head.trusted() && head.current()
                && policy.trustedHeadAuthorityIds().contains(head.authorityId())
                && snapshot.subjectSha256().equals(head.subjectSha256())
                && snapshot.policySha256().equals(head.policySha256())
                && snapshot.snapshotSha256().equals(head.headSnapshotSha256())
                && snapshot.revision() == head.headRevision()
                && head.verifiedAtMs() >= snapshot.lastEvaluatedAtMs()
                && head.verifiedAtMs() <= nowMs
                && head.blocker().isBlank();
    }

    /** Revalidates persisted facts; a valid outer snapshot digest is never treated as enough. */
    private boolean validRestoredSnapshot(OperationsSnapshot snapshot, long nowMs) {
        boolean recoveryShape = snapshot.state() == OperationsState.RECOVERY_PENDING
                ? snapshot.pendingRecovery() != null && snapshot.recoveryOriginState() != null
                : snapshot.pendingRecovery() == null && snapshot.recoveryApprovals().isEmpty()
                && snapshot.recoveryOriginState() == null;
        if (!recoveryShape) return false;
        boolean requiresTakeoverLease = snapshot.state() == OperationsState.MANUAL_TAKEOVER
                || snapshot.state() == OperationsState.RECOVERY_PENDING
                && snapshot.recoveryOriginState() == OperationsState.MANUAL_TAKEOVER;
        boolean takeoverShape = requiresTakeoverLease
                ? snapshot.manualTakeoverLease() != null
                : snapshot.manualTakeoverLease() == null;
        if (!takeoverShape) return false;
        if (snapshot.manualTakeoverLease() != null
                && (!snapshot.manualTakeoverLease().hasValidDigest()
                || !policy.subjectSha256().equals(
                snapshot.manualTakeoverLease().subjectSha256())
                || !policy.trustedOperatorVerifierIds().contains(
                snapshot.manualTakeoverLease().verifierId())
                || !OperationsHashing.safeText(
                snapshot.manualTakeoverLease().operatorId(), 128)
                || !OperationsHashing.safeText(
                snapshot.manualTakeoverLease().replayReservationId(), 128)
                || !OperationsHashing.isSha256(
                snapshot.manualTakeoverLease().basisSnapshotSha256())
                || !OperationsHashing.isSha256(
                snapshot.manualTakeoverLease().attestationSha256())
                || snapshot.manualTakeoverLease().issuedAtMs() <= 0
                || snapshot.manualTakeoverLease().issuedAtMs() > nowMs
                || snapshot.manualTakeoverLease().expiresAtMs()
                <= snapshot.manualTakeoverLease().issuedAtMs()
                || snapshot.manualTakeoverLease().expiresAtMs()
                - snapshot.manualTakeoverLease().issuedAtMs()
                > policy.maximumApprovalTtlMs())) return false;
        if (snapshot.manualTakeoverLease() != null
                && !trustedPersistedManualTakeover(snapshot.manualTakeoverLease(), nowMs)) {
            return false;
        }
        if (snapshot.pendingRecovery() != null
                && (!snapshot.pendingRecovery().hasValidDigest()
                || snapshot.pendingRecovery().target() == null
                || !policy.subjectSha256().equals(snapshot.pendingRecovery().subjectSha256())
                || snapshot.pendingRecovery().requestedAtMs() <= 0
                || snapshot.pendingRecovery().requestedAtMs() > nowMs
                || snapshot.pendingRecovery().expiresAtMs()
                <= snapshot.pendingRecovery().requestedAtMs()
                || snapshot.pendingRecovery().expiresAtMs()
                - snapshot.pendingRecovery().requestedAtMs()
                > policy.maximumRecoveryTtlMs()
                || !OperationsHashing.isSha256(
                snapshot.pendingRecovery().basisSnapshotSha256())
                || !OperationsHashing.isSha256(snapshot.pendingRecovery().purposeSha256())
                || !validRestoredRecoveryStep(snapshot))) {
            return false;
        }
        if (snapshot.pendingRecovery() != null && snapshot.recoveryApprovals().stream()
                .anyMatch(approval -> !snapshot.pendingRecovery().requestSha256()
                        .equals(approval.requestSha256())
                        || approval.verifiedAtMs() < snapshot.pendingRecovery().requestedAtMs()
                        || approval.expiresAtMs() <= approval.verifiedAtMs()
                        || approval.expiresAtMs() - approval.verifiedAtMs()
                        > policy.maximumApprovalTtlMs()
                        || !policy.trustedOperatorVerifierIds().contains(approval.verifierId())
                        || !OperationsHashing.safeText(approval.operatorId(), 128)
                        || !OperationsHashing.safeText(approval.replayReservationId(), 128)
                        || !OperationsHashing.isSha256(approval.attestationSha256()))) return false;
        if (snapshot.pendingRecovery() != null) {
            for (RecoveryApprovalReceipt approval : snapshot.recoveryApprovals()) {
                if (!trustedPersistedRecoveryApproval(
                        approval, snapshot.pendingRecovery(), nowMs)) return false;
            }
        }
        for (Map.Entry<OperationsObservationKind, OperationsObservation> entry
                : snapshot.latestObservations().entrySet()) {
            OperationsObservation observation = entry.getValue();
            if (!entry.getKey().name().equals(observation.inputType())
                    || !policy.authoritativeSources().get(entry.getKey()).equals(observation.source())
                    || observation.sequence() <= 0
                    || observation.observedAtMs() <= 0
                    || observation.expiresAtMs() <= observation.observedAtMs()
                    || observation.expiresAtMs() - observation.observedAtMs()
                    > policy.maximumObservationTtlMs()
                    || observation.health() == null
                    || !policy.subjectSha256().equals(observation.subjectSha256())
                    || !OperationsHashing.isSha256(observation.payloadSha256())
                    || !OperationsHashing.isSha256(observation.attestationSha256())
                    || !observation.hasValidDigest()
                    || !trustedObservation(observation, nowMs)) return false;
        }
        return true;
    }

    private boolean trustedPersistedManualTakeover(ManualTakeoverLease lease, long nowMs) {
        PersistedControlVerification verification;
        try {
            verification = persistedControlVerifier.verifyManualTakeover(lease, policy, nowMs);
        } catch (RuntimeException verificationFailure) {
            return false;
        }
        return trustedPersistedControl(verification, lease.operatorId(),
                OperationsWatchdogPolicy.MANUAL_TAKEOVER_ROLE, lease.leaseSha256(),
                lease.attestationSha256(), lease.replayReservationId(), lease.issuedAtMs(), nowMs);
    }

    private boolean trustedPersistedRecoveryApproval(RecoveryApprovalReceipt receipt,
                                                      RecoveryRequest request,
                                                      long nowMs) {
        PersistedControlVerification verification;
        try {
            verification = persistedControlVerifier.verifyRecoveryApproval(
                    receipt, request, policy, nowMs);
        } catch (RuntimeException verificationFailure) {
            return false;
        }
        return trustedPersistedControl(verification, receipt.operatorId(),
                OperationsWatchdogPolicy.RECOVERY_APPROVER_ROLE, receipt.receiptSha256(),
                receipt.attestationSha256(), receipt.replayReservationId(),
                receipt.verifiedAtMs(), nowMs);
    }

    private boolean trustedPersistedControl(PersistedControlVerification verification,
                                            String operatorId,
                                            String role,
                                            String controlSha256,
                                            String attestationSha256,
                                            String replayReservationId,
                                            long earliestVerificationMs,
                                            long nowMs) {
        return verification != null && verification.trusted()
                && policy.trustedOperatorVerifierIds().contains(verification.verifierId())
                && operatorId.equals(verification.verifiedOperatorId())
                && role.equals(verification.verifiedRole())
                && policy.subjectSha256().equals(verification.verifiedSubjectSha256())
                && policy.policySha256().equals(verification.verifiedPolicySha256())
                && controlSha256.equals(verification.verifiedControlSha256())
                && attestationSha256.equals(verification.recomputedAttestationSha256())
                && replayReservationId.equals(verification.verifiedReplayReservationId())
                && verification.verifiedAtMs() >= earliestVerificationMs
                && verification.verifiedAtMs() <= nowMs
                && verification.blocker().isBlank();
    }

    private boolean validRestoredRecoveryStep(OperationsSnapshot snapshot) {
        return switch (snapshot.recoveryOriginState()) {
            case HALTED, MANUAL_TAKEOVER ->
                    snapshot.pendingRecovery().target() == RecoveryTarget.DEGRADED;
            case DEGRADED -> snapshot.pendingRecovery().target() == RecoveryTarget.NORMAL;
            case NORMAL, RECOVERY_PENDING -> false;
        };
    }

    private OperationsDecision halt(OperationsSnapshot snapshot,
                                    OperationsReason reason,
                                    String detail,
                                    long nowMs) {
        return halt(snapshot, snapshot.latestObservations(), reason, detail, nowMs);
    }

    private OperationsDecision halt(OperationsSnapshot snapshot,
                                    Map<OperationsObservationKind, OperationsObservation> observations,
                                    OperationsReason reason,
                                    String detail,
                                    long nowMs) {
        OperationsSnapshot halted = next(snapshot, OperationsState.HALTED, observations, null,
                List.of(), reason, nowMs);
        return decision(false, reason, detail, halted);
    }

    private OperationsSnapshot next(OperationsSnapshot current,
                                    OperationsState state,
                                    Map<OperationsObservationKind, OperationsObservation> observations,
                                    RecoveryRequest recovery,
                                    List<RecoveryApprovalReceipt> approvals,
                                    OperationsReason reason,
                                    long nowMs) {
        return next(current, state, observations, recovery, approvals, reason, nowMs,
                current.lastConsumedControlCommandSha256());
    }

    private OperationsSnapshot next(OperationsSnapshot current,
                                    OperationsState state,
                                    Map<OperationsObservationKind, OperationsObservation> observations,
                                    RecoveryRequest recovery,
                                    List<RecoveryApprovalReceipt> approvals,
                                    OperationsReason reason,
                                    long nowMs,
                                    String consumedControlCommandSha256) {
        ManualTakeoverLease takeoverLease = state == OperationsState.MANUAL_TAKEOVER
                ? current.manualTakeoverLease()
                : state == OperationsState.RECOVERY_PENDING
                && (current.state() == OperationsState.MANUAL_TAKEOVER
                || current.recoveryOriginState() == OperationsState.MANUAL_TAKEOVER)
                ? current.manualTakeoverLease() : null;
        OperationsState recoveryOrigin = state == OperationsState.RECOVERY_PENDING
                ? current.state() == OperationsState.RECOVERY_PENDING
                ? current.recoveryOriginState() : current.state()
                : null;
        return OperationsSnapshot.create(current.revision() + 1,
                Math.max(current.lastEvaluatedAtMs(), nowMs), state, policy.subjectSha256(),
                policy.policySha256(), current.snapshotSha256(),
                consumedControlCommandSha256, observations, takeoverLease,
                recoveryOrigin, recovery, approvals, reason);
    }

    private TimeCheck checkTime(OperationsSnapshot current, long suppliedNowMs) {
        long trustedNowMs;
        try {
            trustedNowMs = trustedClock.millis();
        } catch (RuntimeException unavailable) {
            trustedNowMs = Long.MAX_VALUE;
        }
        boolean invalid = trustedNowMs <= 0 || suppliedNowMs != trustedNowMs
                || current != null && trustedNowMs < current.lastEvaluatedAtMs();
        if (!invalid) return TimeCheck.valid(trustedNowMs);
        long failClosedTime = current == null
                ? Math.max(1, trustedNowMs)
                : Math.max(current.lastEvaluatedAtMs(), Math.max(1, trustedNowMs));
        long failClosedRevision = current == null ? 0
                : current.revision() == Long.MAX_VALUE ? Long.MAX_VALUE : current.revision() + 1;
        Map<OperationsObservationKind, OperationsObservation> retainedFacts = current != null
                && policy.subjectSha256().equals(current.subjectSha256())
                && policy.policySha256().equals(current.policySha256())
                && current.hasValidDigest() ? current.latestObservations() : Map.of();
        String predecessor = current != null && current.hasValidDigest()
                ? current.snapshotSha256() : "0".repeat(64);
        OperationsSnapshot halted = OperationsSnapshot.create(failClosedRevision, failClosedTime,
                OperationsState.HALTED, policy.subjectSha256(), policy.policySha256(), predecessor,
                current == null ? "0".repeat(64) : current.lastConsumedControlCommandSha256(),
                retainedFacts, null, null, null, List.of(),
                OperationsReason.TIME_AUTHORITY_MISMATCH);
        return TimeCheck.invalid(decision(false, OperationsReason.TIME_AUTHORITY_MISMATCH,
                "caller time differs from the trusted clock, or trusted time moved backwards",
                halted));
    }

    private long authoritativeNowOrFailClosed() {
        try {
            long value = trustedClock.millis();
            return value > 0 ? value : Long.MAX_VALUE;
        } catch (RuntimeException unavailable) {
            return Long.MAX_VALUE;
        }
    }

    private OperationsDecision decision(boolean accepted,
                                        OperationsReason reason,
                                        String detail,
                                        OperationsSnapshot snapshot) {
        return new OperationsDecision(accepted, true, reason, detail, snapshot,
                intents(snapshot, reason, detail));
    }

    private OperationsDecision noOpDecision(boolean accepted,
                                            OperationsReason reason,
                                            String detail,
                                            OperationsSnapshot snapshot) {
        return new OperationsDecision(accepted, false, reason, detail, snapshot, List.of());
    }

    private List<OperationsActionIntent> intents(OperationsSnapshot snapshot,
                                                OperationsReason reason,
                                                String detail) {
        OperationsState state = snapshot.state();
        List<OperationsActionType> types = new ArrayList<>(switch (state) {
            case NORMAL -> List.of(OperationsActionType.CONTINUE_BOUNDED_OPERATION);
            case DEGRADED -> List.of(
                    OperationsActionType.HOLD_RISK_INCREASE,
                    OperationsActionType.ALLOW_VERIFIED_RISK_REDUCTION,
                    OperationsActionType.REQUEST_RECONCILIATION,
                    OperationsActionType.ALERT_HUMAN_OPERATOR,
                    OperationsActionType.DISABLE_AGENT_ACTIONS);
            case HALTED -> List.of(
                    OperationsActionType.HOLD_RISK_INCREASE,
                    OperationsActionType.ALLOW_VERIFIED_RISK_REDUCTION,
                    OperationsActionType.REQUEST_KILL_SWITCH_HALT,
                    OperationsActionType.REQUEST_RECONCILIATION,
                    OperationsActionType.ALERT_HUMAN_OPERATOR,
                    OperationsActionType.DISABLE_AGENT_ACTIONS);
            case MANUAL_TAKEOVER -> List.of(
                    OperationsActionType.HOLD_RISK_INCREASE,
                    OperationsActionType.ALLOW_VERIFIED_RISK_REDUCTION,
                    OperationsActionType.HAND_CONTROL_TO_OPERATOR,
                    OperationsActionType.REQUEST_RECONCILIATION,
                    OperationsActionType.ALERT_HUMAN_OPERATOR,
                    OperationsActionType.DISABLE_AGENT_ACTIONS);
            case RECOVERY_PENDING -> List.of(
                    OperationsActionType.HOLD_RISK_INCREASE,
                    OperationsActionType.ALLOW_VERIFIED_RISK_REDUCTION,
                    OperationsActionType.REQUEST_RECONCILIATION,
                    OperationsActionType.REQUEST_DUAL_CONTROL,
                    OperationsActionType.ALERT_HUMAN_OPERATOR,
                    OperationsActionType.DISABLE_AGENT_ACTIONS);
        });
        if (state == OperationsState.RECOVERY_PENDING
                && snapshot.recoveryOriginState() == OperationsState.HALTED) {
            types.add(OperationsActionType.REQUEST_KILL_SWITCH_HALT);
        }
        if (state == OperationsState.RECOVERY_PENDING
                && snapshot.recoveryOriginState() == OperationsState.MANUAL_TAKEOVER) {
            types.add(OperationsActionType.HAND_CONTROL_TO_OPERATOR);
        }
        return types.stream().map(type -> OperationsActionIntent.create(
                        type, state, reason, detail, snapshot,
                        policy.maximumActionIntentTtlMs()))
                .toList();
    }

    private record ObservationCheck(boolean valid,
                                    OperationsObservationKind kind,
                                    OperationsReason reason,
                                    String detail) {
        static ObservationCheck valid(OperationsObservationKind kind) {
            return new ObservationCheck(true, kind, null, "");
        }

        static ObservationCheck invalid(OperationsReason reason, String detail) {
            return new ObservationCheck(false, null, reason, detail);
        }
    }

    private record FactProblem(OperationsReason reason,
                               String detail,
                               OperationsHealth health) {
    }

    private record SnapshotCheck(boolean valid,
                                 OperationsSnapshot snapshot,
                                 OperationsDecision failure) {
        static SnapshotCheck valid(OperationsSnapshot snapshot) {
            return new SnapshotCheck(true, snapshot, null);
        }

        static SnapshotCheck invalid(OperationsDecision failure) {
            return new SnapshotCheck(false, null, failure);
        }
    }

    private record TimeCheck(boolean valid,
                             long trustedNowMs,
                             OperationsDecision failure) {
        static TimeCheck valid(long trustedNowMs) {
            return new TimeCheck(true, trustedNowMs, null);
        }

        static TimeCheck invalid(OperationsDecision failure) {
            return new TimeCheck(false, 0, failure);
        }
    }
}
