package com.tj.crypto.autonomy.canary;

import java.util.Optional;

@FunctionalInterface
public interface CapitalApprovalKeyProvider {
    Optional<TrustedCapitalApprovalKey> findByIssuer(String issuer);
}
