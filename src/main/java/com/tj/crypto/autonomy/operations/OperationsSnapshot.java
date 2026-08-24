package com.tj.crypto.autonomy.operations;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Immutable, content-addressed state carried explicitly through every watchdog decision. */
public record OperationsSnapshot(
        long revision,
        long lastEvaluatedAtMs,
        OperationsState state,
        String subjectSha256,
        String policySha256,
        String previousSnapshotSha256,
        String lastConsumedControlCommandSha256,
        Map<OperationsObservationKind, OperationsObservation> latestObservations,
        ManualTakeoverLease manualTakeoverLease,
        OperationsState recoveryOriginState,
        RecoveryRequest pendingRecovery,
        List<RecoveryApprovalReceipt> recoveryApprovals,
        OperationsReason lastReason,
        String snapshotSha256
) {
    public OperationsSnapshot {
        if (revision < 0 || lastEvaluatedAtMs <= 0 || state == null || lastReason == null
                || !OperationsHashing.isSha256(subjectSha256)
                || !OperationsHashing.isSha256(policySha256)
                || !OperationsHashing.isSha256(previousSnapshotSha256)
                || !OperationsHashing.isSha256(lastConsumedControlCommandSha256)) {
            throw new IllegalArgumentException("operations snapshot metadata is invalid");
        }
        boolean genesis = "0".repeat(64).equals(previousSnapshotSha256);
        if ((revision == 0) != genesis) {
            throw new IllegalArgumentException("snapshot revision and predecessor are inconsistent");
        }
        EnumMap<OperationsObservationKind, OperationsObservation> observationCopy =
                new EnumMap<>(OperationsObservationKind.class);
        if (latestObservations != null) observationCopy.putAll(latestObservations);
        if (observationCopy.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getValue() == null
                || OperationsObservationKind.parse(entry.getValue().inputType()).orElse(null)
                != entry.getKey())) {
            throw new IllegalArgumentException("operations snapshot contains a mismatched fact");
        }
        latestObservations = Map.copyOf(observationCopy);
        List<RecoveryApprovalReceipt> approvalCopy = recoveryApprovals == null
                ? new ArrayList<>() : new ArrayList<>(recoveryApprovals);
        if (approvalCopy.stream().anyMatch(receipt -> receipt == null)) {
            throw new IllegalArgumentException("operations snapshot contains a null approval receipt");
        }
        approvalCopy.sort(Comparator.comparing(RecoveryApprovalReceipt::operatorId,
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(RecoveryApprovalReceipt::approvalSha256));
        if (approvalCopy.stream().anyMatch(receipt -> !receipt.hasValidDigest())) {
            throw new IllegalArgumentException("operations snapshot contains an invalid approval receipt");
        }
        recoveryApprovals = List.copyOf(approvalCopy);
        String recomputed = computeDigest(revision, lastEvaluatedAtMs, state, subjectSha256,
                policySha256, previousSnapshotSha256, latestObservations,
                lastConsumedControlCommandSha256, manualTakeoverLease, recoveryOriginState,
                pendingRecovery, recoveryApprovals, lastReason);
        if (!OperationsHashing.isSha256(snapshotSha256) || !snapshotSha256.equals(recomputed)) {
            throw new IllegalArgumentException("operations snapshot digest mismatch");
        }
    }

    public static OperationsSnapshot failClosedInitial(String subjectSha256,
                                                        String policySha256,
                                                        long evaluatedAtMs) {
        return create(0, evaluatedAtMs, OperationsState.HALTED, subjectSha256, policySha256,
                "0".repeat(64), "0".repeat(64), Map.of(), null, null, null, List.of(),
                OperationsReason.STARTUP_FAIL_CLOSED);
    }

    static OperationsSnapshot create(long revision,
                                     long evaluatedAtMs,
                                     OperationsState state,
                                     String subjectSha256,
                                     String policySha256,
                                     String previousSnapshotSha256,
                                     String lastConsumedControlCommandSha256,
                                     Map<OperationsObservationKind, OperationsObservation> observations,
                                     ManualTakeoverLease manualTakeoverLease,
                                     OperationsState recoveryOriginState,
                                     RecoveryRequest recovery,
                                     List<RecoveryApprovalReceipt> approvals,
                                     OperationsReason reason) {
        Map<OperationsObservationKind, OperationsObservation> safeObservations =
                observations == null ? Map.of() : observations;
        List<RecoveryApprovalReceipt> safeApprovals = approvals == null ? List.of() : approvals;
        String digest = computeDigest(revision, evaluatedAtMs, state, subjectSha256,
                policySha256, previousSnapshotSha256, safeObservations,
                lastConsumedControlCommandSha256, manualTakeoverLease, recoveryOriginState,
                recovery, safeApprovals, reason);
        return new OperationsSnapshot(revision, evaluatedAtMs, state, subjectSha256,
                policySha256, previousSnapshotSha256, lastConsumedControlCommandSha256,
                safeObservations, manualTakeoverLease, recoveryOriginState,
                recovery, safeApprovals, reason, digest);
    }

    public boolean hasValidDigest() {
        return snapshotSha256.equals(computeDigest(revision, lastEvaluatedAtMs, state,
                subjectSha256, policySha256, previousSnapshotSha256,
                latestObservations, lastConsumedControlCommandSha256, manualTakeoverLease,
                recoveryOriginState, pendingRecovery, recoveryApprovals, lastReason));
    }

    private static String computeDigest(long revision,
                                        long evaluatedAtMs,
                                        OperationsState state,
                                        String subjectSha256,
                                        String policySha256,
                                        String previousSnapshotSha256,
                                        Map<OperationsObservationKind, OperationsObservation> observations,
                                        String lastConsumedControlCommandSha256,
                                        ManualTakeoverLease manualTakeoverLease,
                                        OperationsState recoveryOriginState,
                                        RecoveryRequest recovery,
                                        List<RecoveryApprovalReceipt> approvals,
                                        OperationsReason reason) {
        List<Object> fields = new ArrayList<>();
        fields.add("operations-watchdog-snapshot/v1");
        fields.add(revision);
        fields.add(evaluatedAtMs);
        fields.add(state == null ? null : state.name());
        fields.add(subjectSha256);
        fields.add(policySha256);
        fields.add(previousSnapshotSha256);
        fields.add(lastConsumedControlCommandSha256);
        fields.add(reason == null ? null : reason.name());
        for (OperationsObservationKind kind : OperationsObservationKind.values()) {
            OperationsObservation observation = observations == null ? null : observations.get(kind);
            fields.add(kind.name());
            fields.add(observation == null ? null : observation.observationSha256());
        }
        fields.add(manualTakeoverLease == null ? null : manualTakeoverLease.leaseSha256());
        fields.add(recoveryOriginState == null ? null : recoveryOriginState.name());
        fields.add(recovery == null ? null : recovery.requestSha256());
        List<RecoveryApprovalReceipt> sorted = approvals == null
                ? new ArrayList<>() : new ArrayList<>(approvals);
        sorted.sort(Comparator.comparing(RecoveryApprovalReceipt::operatorId,
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(RecoveryApprovalReceipt::approvalSha256));
        for (RecoveryApprovalReceipt receipt : sorted) fields.add(receipt.receiptSha256());
        return OperationsHashing.sha256(fields.toArray());
    }
}
