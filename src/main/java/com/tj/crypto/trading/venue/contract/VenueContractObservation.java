package com.tj.crypto.trading.venue.contract;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Raw, redacted output of one contract probe. Evaluation never trusts a probe-supplied pass flag. */
public record VenueContractObservation(
        VenueContractScenario scenario,
        long observedAtMs,
        long observedDurationMs,
        String corpusId,
        String rawEvidenceSha256,
        Map<String, String> facts,
        List<VenueContractAction> actions,
        String summary
) {
    public VenueContractObservation {
        if (scenario == null || scenario == VenueContractScenario.PROFILE_BOUNDARY) {
            throw new IllegalArgumentException("A probe scenario other than PROFILE_BOUNDARY is required");
        }
        if (observedAtMs <= 0 || observedDurationMs < 0) {
            throw new IllegalArgumentException("observation time and duration are invalid");
        }
        if (corpusId == null || corpusId.isBlank()) {
            throw new IllegalArgumentException("corpusId is required");
        }
        if (!VenueContractEvidenceDigest.isSha256(rawEvidenceSha256)) {
            throw new IllegalArgumentException("rawEvidenceSha256 must be a SHA-256 digest");
        }
        facts = facts == null ? Map.of() : Map.copyOf(new TreeMap<>(facts));
        actions = actions == null ? List.of() : List.copyOf(new ArrayList<>(actions));
        int previous = 0;
        for (VenueContractAction action : actions) {
            if (action.sequence() <= previous) {
                throw new IllegalArgumentException("Action sequence must be strictly increasing");
            }
            previous = action.sequence();
        }
        if (summary == null || summary.isBlank()) throw new IllegalArgumentException("summary is required");
    }
}
