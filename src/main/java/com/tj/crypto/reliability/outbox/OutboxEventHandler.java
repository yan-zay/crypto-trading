package com.tj.crypto.reliability.outbox;

/** Idempotent internal or external projection fed by the transactional outbox. */
public interface OutboxEventHandler {
    String consumerName();

    boolean supports(String eventType);

    void handle(OutboxEventDO event);
}
