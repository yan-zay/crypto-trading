package com.tj.crypto.autonomy.operations;

/** A side-effect-free instruction for an independently authorized integration layer. */
public record OperationsActionIntent(
        OperationsActionType type,
        OperationsState state,
        OperationsReason reason,
        String detail,
        String subjectSha256,
        String policySha256,
        String expectedPreviousSnapshotSha256,
        String resultSnapshotSha256,
        long resultRevision,
        long issuedAtMs,
        long expiresAtMs,
        String singleUseIdSha256,
        String intentSha256
) {
    private static final String AUDIENCE = "operations-action-intent/v1";

    public OperationsActionIntent {
        if (type == null || state == null || reason == null) {
            throw new IllegalArgumentException("action type, state and reason are required");
        }
        detail = detail == null ? "" : detail;
        if (detail.length() > 500 || detail.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("action detail is invalid");
        }
        if (!OperationsHashing.isSha256(subjectSha256)
                || !OperationsHashing.isSha256(policySha256)
                || !OperationsHashing.isSha256(expectedPreviousSnapshotSha256)
                || !OperationsHashing.isSha256(resultSnapshotSha256)
                || resultRevision < 0 || issuedAtMs <= 0 || expiresAtMs < issuedAtMs
                || !OperationsHashing.isSha256(singleUseIdSha256)
                || !singleUseIdSha256.equals(useId(type, subjectSha256, policySha256,
                expectedPreviousSnapshotSha256, resultSnapshotSha256, resultRevision))
                || !OperationsHashing.isSha256(intentSha256)
                || !intentSha256.equals(digest(type, state, reason, detail,
                subjectSha256, policySha256, expectedPreviousSnapshotSha256,
                resultSnapshotSha256, resultRevision, issuedAtMs, expiresAtMs,
                singleUseIdSha256))) {
            throw new IllegalArgumentException("action intent lineage or digest is invalid");
        }
    }

    static OperationsActionIntent create(OperationsActionType type,
                                         OperationsState state,
                                         OperationsReason reason,
                                         String detail,
                                         OperationsSnapshot result,
                                         long maximumTtlMs) {
        long expiresAtMs = result.lastEvaluatedAtMs() > Long.MAX_VALUE - maximumTtlMs
                ? Long.MAX_VALUE : result.lastEvaluatedAtMs() + maximumTtlMs;
        String useId = useId(type, result.subjectSha256(), result.policySha256(),
                result.previousSnapshotSha256(), result.snapshotSha256(), result.revision());
        String digest = digest(type, state, reason, detail, result.subjectSha256(),
                result.policySha256(), result.previousSnapshotSha256(), result.snapshotSha256(),
                result.revision(), result.lastEvaluatedAtMs(), expiresAtMs, useId);
        return new OperationsActionIntent(type, state, reason, detail,
                result.subjectSha256(), result.policySha256(), result.previousSnapshotSha256(),
                result.snapshotSha256(), result.revision(), result.lastEvaluatedAtMs(), expiresAtMs,
                useId, digest);
    }

    private static String digest(OperationsActionType type,
                                 OperationsState state,
                                 OperationsReason reason,
                                 String detail,
                                 String subjectSha256,
                                 String policySha256,
                                 String expectedPreviousSnapshotSha256,
                                 String resultSnapshotSha256,
                                 long resultRevision,
                                 long issuedAtMs,
                                 long expiresAtMs,
                                 String singleUseIdSha256) {
        return OperationsHashing.sha256(AUDIENCE, type == null ? null : type.name(),
                state == null ? null : state.name(), reason == null ? null : reason.name(), detail,
                subjectSha256, policySha256, expectedPreviousSnapshotSha256, resultSnapshotSha256,
                resultRevision, issuedAtMs, expiresAtMs, singleUseIdSha256);
    }

    private static String useId(OperationsActionType type,
                                String subjectSha256,
                                String policySha256,
                                String expectedPreviousSnapshotSha256,
                                String resultSnapshotSha256,
                                long resultRevision) {
        return OperationsHashing.sha256("operations-action-use/v1",
                type == null ? null : type.name(), subjectSha256, policySha256,
                expectedPreviousSnapshotSha256, resultSnapshotSha256, resultRevision);
    }
}
