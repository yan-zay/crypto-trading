package com.tj.crypto.strategy.lifecycle;

import java.time.Instant;

public record TrustedSigningKey(
        String keyId,
        String algorithm,
        String publicKeyX509Base64,
        String status,
        Instant validFrom,
        Instant expiresAt,
        Instant revokedAt) {

    public boolean trustedAt(Instant at) {
        return "ACTIVE".equals(status)
                && !validFrom.isAfter(at)
                && expiresAt.isAfter(at)
                && (revokedAt == null || revokedAt.isAfter(at));
    }
}
