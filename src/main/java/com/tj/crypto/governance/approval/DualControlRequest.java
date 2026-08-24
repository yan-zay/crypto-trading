package com.tj.crypto.governance.approval;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Immutable approval request bound to the SHA-256 of the exact action payload. */
public record DualControlRequest(String requestId,
                                 ApprovalAction action,
                                 String resourceSha256,
                                 String governanceSubjectSha256,
                                 String requestedBy,
                                 long createdAtMs,
                                 long expiresAtMs,
                                 List<ApprovalVote> votes) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public DualControlRequest {
        requestId = required(requestId, "requestId");
        if (action == null) throw new IllegalArgumentException("action is required");
        resourceSha256 = required(resourceSha256, "resourceSha256").toLowerCase(Locale.ROOT);
        governanceSubjectSha256 = required(
                governanceSubjectSha256, "governanceSubjectSha256").toLowerCase(Locale.ROOT);
        requestedBy = required(requestedBy, "requestedBy");
        if (!SHA_256.matcher(resourceSha256).matches()
                || !SHA_256.matcher(governanceSubjectSha256).matches()) {
            throw new IllegalArgumentException(
                    "resource and governance subject must be lowercase SHA-256 values");
        }
        if (createdAtMs <= 0 || expiresAtMs <= createdAtMs) {
            throw new IllegalArgumentException("approval request timestamps are invalid");
        }
        votes = List.copyOf(votes == null ? List.of() : votes);
    }

    private static String required(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return normalized;
    }
}
