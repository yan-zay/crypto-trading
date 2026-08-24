package com.tj.crypto.autonomy.canary;

import java.time.Instant;
import java.util.Locale;

/** Public trust root provisioned independently of a decision request. */
public record TrustedCapitalApprovalKey(
        String issuer,
        String algorithm,
        String publicKeyX509Base64,
        Instant validFrom,
        Instant expiresAt,
        Instant revokedAt
) {
    public TrustedCapitalApprovalKey {
        issuer = CanaryCanonical.required(issuer, "issuer");
        algorithm = CanaryCanonical.required(algorithm, "algorithm").toUpperCase(Locale.ROOT);
        publicKeyX509Base64 = CanaryCanonical.required(publicKeyX509Base64, "publicKeyX509Base64");
        if (validFrom == null || expiresAt == null || !expiresAt.isAfter(validFrom)) {
            throw new IllegalArgumentException("trusted key validity window is invalid");
        }
        if (revokedAt != null && revokedAt.isBefore(validFrom)) {
            throw new IllegalArgumentException("revocation cannot precede key validity");
        }
    }

    boolean trustedAt(Instant instant) {
        return !instant.isBefore(validFrom) && instant.isBefore(expiresAt)
                && (revokedAt == null || instant.isBefore(revokedAt));
    }
}
