package com.tj.crypto.autonomy.canary;

import java.time.Instant;

/**
 * Atomic persistence port. Production implementations must compare stateVersion, content digest,
 * deployment scope, and persistence evidence with the durable current head.
 */
@FunctionalInterface
public interface CanaryRunStateVerifier {
    CanaryRunStateVerification verifyCurrentHead(CanaryRunState state, Instant evaluatedAt);

    static CanaryRunStateVerifier notWired() {
        return (state, evaluatedAt) -> CanaryRunStateVerification.NOT_WIRED;
    }
}
