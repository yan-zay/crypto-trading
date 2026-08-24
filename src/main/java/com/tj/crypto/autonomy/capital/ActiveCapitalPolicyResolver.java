package com.tj.crypto.autonomy.capital;

import java.util.Optional;

/**
 * Server-owned port for the unique active signed head of one portfolio policy.
 *
 * <p>A production implementation must use durable CAS activation, consecutive versions,
 * predecessor binding and revocation. Returning an arbitrary historical policy violates this
 * contract; no such implementation is provided by this package.
 */
@FunctionalInterface
public interface ActiveCapitalPolicyResolver {
    Optional<SignedCapitalRiskPolicy> findActive(String portfolioId, String policyId);

    static ActiveCapitalPolicyResolver unavailable() {
        return (portfolioId, policyId) -> Optional.empty();
    }
}
