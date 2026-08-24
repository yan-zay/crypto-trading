package com.tj.crypto.strategy.lifecycle;

/** Capabilities are deliberately finer grained than an application-wide admin role. */
public enum LifecyclePermission {
    SUBMIT_ARTIFACT,
    REQUEST_REVIEW,
    APPROVE_STRATEGY,
    START_SHADOW,
    START_CANARY,
    PROMOTE_STRATEGY,
    ROLLBACK_STRATEGY,
    RECORD_EVIDENCE,
    ASSIGN_RELEASE,
    ENFORCE_POLICY
}
