package com.tj.crypto.autonomy.lineage;

/** A fail-closed order lineage rejection that callers must not downgrade to allow. */
public final class OrderDecisionLineageException extends IllegalStateException {

    private final String reasonCode;

    public OrderDecisionLineageException(String reasonCode, String message) {
        super(message);
        this.reasonCode = OrderDecisionLineageValues.text(reasonCode, "reasonCode");
    }

    public String reasonCode() {
        return reasonCode;
    }
}
