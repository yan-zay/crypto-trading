package com.tj.crypto.autonomy.lineage;

import java.time.Instant;

/**
 * Deterministic admission check only. The void contract intentionally cannot return a gateway
 * permit, order command, credential, signer or any other authority-bearing object.
 */
@FunctionalInterface
public interface OrderDecisionAdmissionPort {

    void requireAdmissible(OrderDecisionEnvelope envelope, Instant admissionTime);

    static OrderDecisionAdmissionPort unavailable() {
        return (envelope, admissionTime) -> {
            if (envelope == null || admissionTime == null) {
                throw new IllegalArgumentException("envelope and admissionTime are required");
            }
            throw new OrderDecisionLineageException(
                    "ORDER_DECISION_ADMISSION_UNAVAILABLE", "Order-decision admission is not wired");
        };
    }
}
