package com.tj.crypto.autonomy.lineage;

/** Append-only persistence port. No implementation is wired by default. */
@FunctionalInterface
public interface OrderDecisionStore {

    void append(OrderDecisionEnvelope envelope);

    static OrderDecisionStore unavailable() {
        return envelope -> {
            if (envelope == null) throw new IllegalArgumentException("envelope is required");
            throw new OrderDecisionLineageException(
                    "ORDER_DECISION_STORE_UNAVAILABLE", "Order-decision store is not wired");
        };
    }
}
