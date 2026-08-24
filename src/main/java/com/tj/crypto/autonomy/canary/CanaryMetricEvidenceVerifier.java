package com.tj.crypto.autonomy.canary;

import java.time.Instant;

/**
 * Port owned by trusted infrastructure, not by the metric request producer. A production
 * implementation must resolve the immutable source manifest and bind its trusted receipt to the
 * snapshot content digest and deployment scope. Returning anything except VERIFIED is fail-closed.
 */
@FunctionalInterface
public interface CanaryMetricEvidenceVerifier {
    CanaryMetricEvidenceVerification verify(CanaryMetricSnapshot snapshot, Instant evaluatedAt);

    static CanaryMetricEvidenceVerifier notWired() {
        return (snapshot, evaluatedAt) -> CanaryMetricEvidenceVerification.NOT_WIRED;
    }
}
