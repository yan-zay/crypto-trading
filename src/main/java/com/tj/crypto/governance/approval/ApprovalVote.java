package com.tj.crypto.governance.approval;

import java.util.Locale;

public record ApprovalVote(String approver,
                           String role,
                           boolean approved,
                           long decidedAtMs,
                           MfaAttestation mfaAttestation,
                           String comment) {
    public ApprovalVote {
        approver = required(approver, "approver");
        role = required(role, "role").toUpperCase(Locale.ROOT);
        if (decidedAtMs <= 0) throw new IllegalArgumentException("decidedAtMs must be positive");
        if (mfaAttestation == null) throw new IllegalArgumentException("MFA attestation is required");
        comment = comment == null ? "" : comment.trim();
    }

    private static String required(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return normalized;
    }
}
