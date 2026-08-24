package com.tj.crypto.autonomy.lineage;

/** Account, hard-risk and valuation facts observed before the decision was created. */
public record DecisionSnapshotLineage(
        SnapshotReference accountSnapshot,
        SnapshotReference riskSnapshot,
        SnapshotReference valuationSnapshot) {

    public DecisionSnapshotLineage {
        if (accountSnapshot == null || riskSnapshot == null || valuationSnapshot == null) {
            throw new IllegalArgumentException("account, risk and valuation snapshots are required");
        }
    }
}
