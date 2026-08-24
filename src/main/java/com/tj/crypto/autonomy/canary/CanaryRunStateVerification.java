package com.tj.crypto.autonomy.canary;

/** Result of resolving a run snapshot against the durable current-head owner. */
public enum CanaryRunStateVerification {
    VERIFIED,
    NOT_WIRED,
    HEAD_NOT_FOUND,
    HEAD_DIGEST_MISMATCH,
    STALE_VERSION,
    VERIFIER_ERROR
}
