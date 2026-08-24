package com.tj.crypto.autonomy.canary;

/** Result of independently resolving a metric snapshot against trusted immutable facts. */
public enum CanaryMetricEvidenceVerification {
    VERIFIED,
    NOT_WIRED,
    MANIFEST_NOT_FOUND,
    MANIFEST_MISMATCH,
    PRODUCER_NOT_TRUSTED,
    RECEIPT_EXPIRED,
    VERIFIER_ERROR
}
