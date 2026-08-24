package com.tj.crypto.autonomy.capital;

import java.time.Instant;

/**
 * Deterministic capital/risk lookup result.
 *
 * <p>A resolved envelope is not an OMS reservation, venue capability or execution permit.
 */
public final class CapitalEnvelopeResolution {
    private final CapitalResolutionCode code;
    private final String message;
    private final String portfolioId;
    private final String policyId;
    private final long policyVersion;
    private final String policySha256;
    private final RiskScope scope;
    private final Instant evaluatedAt;
    private final Instant expiresAt;
    private final Money allocatedCapital;
    private final RiskLimits riskLimits;

    CapitalEnvelopeResolution(CapitalResolutionCode code, String message, String portfolioId,
                              String policyId, long policyVersion, String policySha256, RiskScope scope,
                              Instant evaluatedAt, Instant expiresAt, Money allocatedCapital,
                              RiskLimits riskLimits) {
        this.code = code;
        this.message = message;
        this.portfolioId = portfolioId;
        this.policyId = policyId;
        this.policyVersion = policyVersion;
        this.policySha256 = policySha256;
        this.scope = scope;
        this.evaluatedAt = evaluatedAt;
        this.expiresAt = expiresAt;
        this.allocatedCapital = allocatedCapital;
        this.riskLimits = riskLimits;
    }

    public CapitalResolutionCode code() { return code; }
    public String message() { return message; }
    public String portfolioId() { return portfolioId; }
    public String policyId() { return policyId; }
    public long policyVersion() { return policyVersion; }
    public String policySha256() { return policySha256; }
    public RiskScope scope() { return scope; }
    public Instant evaluatedAt() { return evaluatedAt; }
    public Instant expiresAt() { return expiresAt; }
    public Money allocatedCapital() { return allocatedCapital; }
    public RiskLimits riskLimits() { return riskLimits; }

    public boolean resolved() {
        return code == CapitalResolutionCode.RESOLVED;
    }

    /** This kernel never grants an order-writing capability. */
    public boolean executionAuthorized() {
        return false;
    }
}
