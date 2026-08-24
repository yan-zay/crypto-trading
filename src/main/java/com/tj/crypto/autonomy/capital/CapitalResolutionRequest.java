package com.tj.crypto.autonomy.capital;

import java.util.Objects;

/** Caller expectation bound to one exact policy digest and one exact risk scope. */
public record CapitalResolutionRequest(
        String expectedPortfolioId,
        String expectedPolicyId,
        long expectedPolicyVersion,
        String expectedPolicySha256,
        RiskScope scope,
        Money requestedCapital
) {
    public CapitalResolutionRequest {
        expectedPortfolioId = CapitalValues.id(expectedPortfolioId, "expectedPortfolioId", 192);
        expectedPolicyId = CapitalValues.id(expectedPolicyId, "expectedPolicyId", 192);
        if (expectedPolicyVersion < 1) {
            throw new IllegalArgumentException("expectedPolicyVersion must be positive");
        }
        expectedPolicySha256 = CapitalValues.sha256(
                expectedPolicySha256, "expectedPolicySha256");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(requestedCapital, "requestedCapital");
        if (!requestedCapital.isPositive()) {
            throw new IllegalArgumentException("requestedCapital must be positive");
        }
    }
}
