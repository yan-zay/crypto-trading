package com.tj.crypto.autonomy.readiness;

/** Signed outcome asserted by the external verifier, never by the evidence caller. */
public enum VerificationOutcome {
    PASS,
    FAIL,
    UNKNOWN
}
