package com.tj.crypto.governance.approval;

/** One durable replay reservation bound one-to-one to the approver whose MFA assertion created it. */
public record MfaReplayReservation(String approver, String reservationId) {
    public MfaReplayReservation {
        approver = required(approver, "approver");
        reservationId = required(reservationId, "reservationId");
    }

    private static String required(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 128
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return normalized;
    }
}
