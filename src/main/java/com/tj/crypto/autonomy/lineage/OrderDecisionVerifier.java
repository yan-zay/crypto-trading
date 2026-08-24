package com.tj.crypto.autonomy.lineage;

import java.time.Instant;

/** Independent evidence-verification port. No implementation is wired by default. */
@FunctionalInterface
public interface OrderDecisionVerifier {

    void verify(OrderDecisionEnvelope envelope, Instant verificationTime);

    static OrderDecisionVerifier unavailable() {
        return (envelope, verificationTime) -> {
            if (envelope == null || verificationTime == null) {
                throw new IllegalArgumentException("envelope and verificationTime are required");
            }
            throw new OrderDecisionLineageException(
                    "ORDER_DECISION_VERIFIER_UNAVAILABLE", "Order-decision verifier is not wired");
        };
    }
}
