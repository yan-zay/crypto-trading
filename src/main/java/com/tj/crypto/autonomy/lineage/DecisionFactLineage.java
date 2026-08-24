package com.tj.crypto.autonomy.lineage;

/** Exact market, journal, archive, factor and signal facts used by one decision. */
public record DecisionFactLineage(
        String marketEventId,
        String marketEventSha256,
        String journalEntryId,
        String journalEntrySha256,
        String archiveManifestId,
        String archiveManifestSha256,
        String factorSnapshotId,
        String factorSnapshotSha256,
        String signalId,
        String signalSha256) {

    public DecisionFactLineage {
        marketEventId = OrderDecisionLineageValues.text(marketEventId, "marketEventId");
        marketEventSha256 = OrderDecisionLineageValues.digest(marketEventSha256, "marketEventSha256");
        journalEntryId = OrderDecisionLineageValues.text(journalEntryId, "journalEntryId");
        journalEntrySha256 = OrderDecisionLineageValues.digest(journalEntrySha256, "journalEntrySha256");
        archiveManifestId = OrderDecisionLineageValues.text(archiveManifestId, "archiveManifestId");
        archiveManifestSha256 = OrderDecisionLineageValues.digest(
                archiveManifestSha256, "archiveManifestSha256");
        factorSnapshotId = OrderDecisionLineageValues.text(factorSnapshotId, "factorSnapshotId");
        factorSnapshotSha256 = OrderDecisionLineageValues.digest(
                factorSnapshotSha256, "factorSnapshotSha256");
        signalId = OrderDecisionLineageValues.text(signalId, "signalId");
        signalSha256 = OrderDecisionLineageValues.digest(signalSha256, "signalSha256");
    }
}
