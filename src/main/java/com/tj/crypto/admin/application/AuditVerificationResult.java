package com.tj.crypto.admin.application;

/** Result of a full ADMIN audit-chain recomputation. */
public record AuditVerificationResult(
        boolean valid,
        long verifiedEntries,
        Long lastAuditId,
        String lastHash,
        boolean chainHeadMatches,
        Long failedAuditId,
        String message
) {
}
