package com.tj.crypto.marketdata.connector.certification;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.marketdata.archive.ArchiveEventType;

import java.time.Instant;
import java.util.Set;

/** Versioned connector conformance evidence; it never authorizes private trading. */
public record ConnectorContractEvidence(
        String connectorId,
        Exchange source,
        Set<ArchiveEventType> canonicalEventTypes,
        boolean rawFirstDurable,
        boolean admissionBeforeFanout,
        boolean stableRawIdentity,
        boolean sequenceGapDetection,
        boolean duplicateIdempotency,
        boolean boundedBackpressure,
        boolean reconnectReplayTested,
        String evidenceSha256,
        Instant observedAt,
        Instant expiresAt) {
    public ConnectorContractEvidence {
        if (connectorId == null || connectorId.isBlank() || source == null
                || canonicalEventTypes == null || canonicalEventTypes.isEmpty()
                || evidenceSha256 == null || !evidenceSha256.matches("[0-9a-f]{64}")
                || observedAt == null || expiresAt == null || !expiresAt.isAfter(observedAt)) {
            throw new IllegalArgumentException("complete connector contract evidence is required");
        }
        canonicalEventTypes = Set.copyOf(canonicalEventTypes);
    }
}
