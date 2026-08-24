package com.tj.crypto.autonomy.lineage;

import java.time.Instant;

/** Point-in-time immutable snapshot reference. */
public record SnapshotReference(String snapshotId, String snapshotSha256, Instant observedAt) {

    public SnapshotReference {
        snapshotId = OrderDecisionLineageValues.text(snapshotId, "snapshotId");
        snapshotSha256 = OrderDecisionLineageValues.digest(snapshotSha256, "snapshotSha256");
        observedAt = OrderDecisionLineageValues.millisecondInstant(observedAt, "observedAt");
    }
}
