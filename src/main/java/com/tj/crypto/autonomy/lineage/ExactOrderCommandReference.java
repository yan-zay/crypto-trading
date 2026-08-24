package com.tj.crypto.autonomy.lineage;

/** Canonical exact order command; this is only a reference and is not executable. */
public record ExactOrderCommandReference(
        String orderId,
        String clientOrderId,
        String commandId,
        String commandSchemaVersion,
        String commandSha256) {

    public ExactOrderCommandReference {
        orderId = OrderDecisionLineageValues.text(orderId, "orderId");
        clientOrderId = OrderDecisionLineageValues.text(clientOrderId, "clientOrderId");
        commandId = OrderDecisionLineageValues.text(commandId, "commandId");
        commandSchemaVersion = OrderDecisionLineageValues.text(
                commandSchemaVersion, "commandSchemaVersion");
        commandSha256 = OrderDecisionLineageValues.digest(commandSha256, "commandSha256");
    }
}
