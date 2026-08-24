package com.tj.crypto.marketdata.archive;

import java.util.List;

/** 一次一致 journal 快照到不可变 Parquet 分区的压实结果。 */
public record JournalCompactionReport(
        String journalSnapshotSha256,
        RealtimeCaptureCapability realtimeCaptureCapability,
        int rawMessageCount,
        int canonicalEventCount,
        int normalizationFailureCount,
        List<ArchiveCommit> commits
) {
    public JournalCompactionReport {
        if (journalSnapshotSha256 == null || journalSnapshotSha256.length() != 64
                || realtimeCaptureCapability == null || rawMessageCount < 0
                || canonicalEventCount < 0 || normalizationFailureCount < 0) {
            throw new IllegalArgumentException("journal compaction report is incomplete");
        }
        commits = List.copyOf(commits);
    }
}
