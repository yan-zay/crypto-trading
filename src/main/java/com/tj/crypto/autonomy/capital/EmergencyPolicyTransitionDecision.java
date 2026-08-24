package com.tj.crypto.autonomy.capital;

import java.time.Instant;

/** Auditable result of verifying two signed revisions at a trusted clock instant. */
public final class EmergencyPolicyTransitionDecision {
    private final EmergencyTransitionCode code;
    private final String message;
    private final String currentPolicySha256;
    private final String candidatePolicySha256;
    private final Instant evaluatedAt;

    EmergencyPolicyTransitionDecision(EmergencyTransitionCode code, String message,
                                      String currentPolicySha256, String candidatePolicySha256,
                                      Instant evaluatedAt) {
        this.code = code;
        this.message = message;
        this.currentPolicySha256 = currentPolicySha256;
        this.candidatePolicySha256 = candidatePolicySha256;
        this.evaluatedAt = evaluatedAt;
    }

    public EmergencyTransitionCode code() { return code; }
    public String message() { return message; }
    public String currentPolicySha256() { return currentPolicySha256; }
    public String candidatePolicySha256() { return candidatePolicySha256; }
    public Instant evaluatedAt() { return evaluatedAt; }

    public boolean accepted() {
        return code == EmergencyTransitionCode.ACCEPTED;
    }
}
