package com.tj.crypto.autonomy.operations;

/** Result of atomically appending one transition and moving one subject/policy current head. */
public record OperationsHeadCasResult(
        boolean applied,
        String authorityId,
        String resultingHeadSha256,
        long resultingRevision,
        String blocker
) {
    public OperationsHeadCasResult {
        blocker = blocker == null ? "" : blocker;
    }

    public static OperationsHeadCasResult notApplied(String reason) {
        return new OperationsHeadCasResult(false, "", "", -1,
                reason == null ? "durable CAS was not applied" : reason);
    }
}
