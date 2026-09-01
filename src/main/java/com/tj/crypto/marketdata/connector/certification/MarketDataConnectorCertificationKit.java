package com.tj.crypto.marketdata.connector.certification;

import com.tj.crypto.marketdata.archive.ArchiveEventType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;

/** Deterministic public-market connector admission for research/Paper only. */
public final class MarketDataConnectorCertificationKit {
    public ConnectorCertificationDecision evaluate(ConnectorContractEvidence evidence,
                                                   Set<ArchiveEventType> requiredTypes,
                                                   Instant now) {
        if (evidence == null || requiredTypes == null || requiredTypes.isEmpty() || now == null) {
            throw new IllegalArgumentException("evidence, required types and time are required");
        }
        ArrayList<String> blockers = new ArrayList<>();
        if (now.isBefore(evidence.observedAt()) || !now.isBefore(evidence.expiresAt())) {
            blockers.add("EVIDENCE_NOT_CURRENT");
        }
        if (!evidence.canonicalEventTypes().containsAll(requiredTypes)) blockers.add("EVENT_TYPE_GAP");
        if (!evidence.rawFirstDurable()) blockers.add("RAW_FIRST_NOT_DURABLE");
        if (!evidence.admissionBeforeFanout()) blockers.add("ADMISSION_AFTER_FANOUT");
        if (!evidence.stableRawIdentity()) blockers.add("RAW_IDENTITY_UNSTABLE");
        if (!evidence.sequenceGapDetection()) blockers.add("SEQUENCE_GAP_UNDETECTED");
        if (!evidence.duplicateIdempotency()) blockers.add("DUPLICATE_NOT_IDEMPOTENT");
        if (!evidence.boundedBackpressure()) blockers.add("BACKPRESSURE_UNBOUNDED");
        if (!evidence.reconnectReplayTested()) blockers.add("RECONNECT_REPLAY_NOT_TESTED");
        return new ConnectorCertificationDecision(blockers.isEmpty()
                ? ConnectorCertificationDecision.Status.PAPER_READY
                : ConnectorCertificationDecision.Status.RESEARCH_ONLY,
                blockers, evidence.evidenceSha256());
    }
}
