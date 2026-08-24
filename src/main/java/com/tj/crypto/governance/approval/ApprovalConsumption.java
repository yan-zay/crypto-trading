package com.tj.crypto.governance.approval;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Immutable, exact action authorization written before a decision can be returned as granted. */
public record ApprovalConsumption(String requestId,
                                  ApprovalAction action,
                                  String resourceSha256,
                                  String governanceSubjectSha256,
                                  String requestedBy,
                                  List<String> approvedBy,
                                  List<MfaReplayReservation> mfaReplayReservations,
                                  long requestCreatedAtMs,
                                  long requestExpiresAtMs,
                                  long consumedAtMs) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public ApprovalConsumption {
        requestId = required(requestId, "requestId");
        if (action == null) throw new IllegalArgumentException("action is required");
        resourceSha256 = hash(resourceSha256, "resourceSha256");
        governanceSubjectSha256 = hash(governanceSubjectSha256, "governanceSubjectSha256");
        requestedBy = required(requestedBy, "requestedBy");
        approvedBy = List.copyOf(approvedBy == null ? List.of() : approvedBy);
        mfaReplayReservations = List.copyOf(
                mfaReplayReservations == null ? List.of() : mfaReplayReservations);
        if (approvedBy.size() < 2 || approvedBy.stream().distinct().count() != approvedBy.size()) {
            throw new IllegalArgumentException("two distinct approvers are required");
        }
        if (mfaReplayReservations.size() != approvedBy.size()
                || mfaReplayReservations.stream().map(MfaReplayReservation::reservationId)
                .distinct().count() != mfaReplayReservations.size()) {
            throw new IllegalArgumentException("each approver requires a distinct MFA replay reservation");
        }
        for (int index = 0; index < approvedBy.size(); index++) {
            String approver = required(approvedBy.get(index), "approvedBy");
            if (!approver.equals(mfaReplayReservations.get(index).approver())) {
                throw new IllegalArgumentException(
                        "each MFA replay reservation must be bound to its approver");
            }
        }
        if (requestCreatedAtMs <= 0 || requestExpiresAtMs <= requestCreatedAtMs
                || consumedAtMs < requestCreatedAtMs || consumedAtMs >= requestExpiresAtMs) {
            throw new IllegalArgumentException("approval consumption timestamps are invalid");
        }
    }

    public String approvedByCsv() {
        return approvedBy.stream().sorted().reduce((left, right) -> left + "," + right).orElse("");
    }

    private static String hash(String value, String name) {
        String normalized = required(value, name).toLowerCase(Locale.ROOT);
        if (!SHA_256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256");
        }
        return normalized;
    }

    private static String required(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 128 || normalized.indexOf(',') >= 0
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return normalized;
    }
}
