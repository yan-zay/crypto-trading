package com.tj.crypto.trading.venue.contract;

import java.util.List;

/** One immutable scenario decision with content-addressed evidence and explicit blockers. */
public record VenueContractCheckResult(
        VenueContractScenario scenario,
        Status status,
        long observedAtMs,
        String evidenceSha256,
        String summary,
        List<String> blockers
) {
    public VenueContractCheckResult {
        if (scenario == null || status == null) throw new IllegalArgumentException("scenario and status are required");
        if (observedAtMs <= 0) throw new IllegalArgumentException("observedAtMs must be positive");
        if (!VenueContractEvidenceDigest.isSha256(evidenceSha256)) {
            throw new IllegalArgumentException("evidenceSha256 must be a SHA-256 digest");
        }
        if (summary == null || summary.isBlank()) throw new IllegalArgumentException("summary is required");
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        if ((status == Status.VERIFIED || status == Status.OFFLINE_PASSED) && !blockers.isEmpty()) {
            throw new IllegalArgumentException("Passing checks cannot contain blockers");
        }
        if ((status == Status.FAILED || status == Status.NOT_VERIFIED) && blockers.isEmpty()) {
            throw new IllegalArgumentException("Non-passing checks require blockers");
        }
    }

    public enum Status { NOT_VERIFIED, OFFLINE_PASSED, VERIFIED, FAILED }
}
