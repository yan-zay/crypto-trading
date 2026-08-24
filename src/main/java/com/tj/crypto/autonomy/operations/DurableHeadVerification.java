package com.tj.crypto.autonomy.operations;

/** Independently verified durable current-head claim. */
public record DurableHeadVerification(
        boolean trusted,
        boolean current,
        String authorityId,
        String subjectSha256,
        String policySha256,
        String headSnapshotSha256,
        long headRevision,
        long verifiedAtMs,
        String blocker
) {
    public DurableHeadVerification {
        blocker = blocker == null ? "" : blocker;
    }

    public static DurableHeadVerification untrusted(String reason) {
        return new DurableHeadVerification(false, false, "", "", "", "", -1, 0,
                reason == null ? "durable head is not verified" : reason);
    }
}
