package com.tj.crypto.autonomy.canary;

/** Structured verifier result; there is no caller-supplied approved=true shortcut. */
public enum CapitalApprovalVerification {
    VERIFIED,
    CONTENT_DIGEST_MISMATCH,
    NOT_YET_VALID,
    EXPIRED,
    MALFORMED,
    UNTRUSTED_ISSUER,
    INVALID_SIGNATURE,
    VERIFIER_ERROR
}
