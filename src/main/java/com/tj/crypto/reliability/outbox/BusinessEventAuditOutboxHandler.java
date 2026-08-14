package com.tj.crypto.reliability.outbox;

import com.tj.crypto.admin.application.AuditRecord;
import com.tj.crypto.admin.application.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Set;

/**
 * Durable audit projection for every business event currently emitted through the outbox.
 *
 * <p>{@link OutboxDispatchService} invokes this handler and writes its
 * {@code processed_event} checkpoint in the same transaction. A retry therefore either sees
 * the checkpoint and skips the projection, or applies both the audit entry and checkpoint
 * atomically.</p>
 */
@Component
@RequiredArgsConstructor
public class BusinessEventAuditOutboxHandler implements OutboxEventHandler {
    static final String CONSUMER_NAME = "business-event-audit-v1";

    private static final Set<String> SUPPORTED_EVENT_TYPES = Set.of(
            "DATASET_EXPORT_COMPLETED",
            "PAPER_ACCOUNT_STARTED",
            "PAPER_ACCOUNT_STOPPED",
            "PAPER_ACCOUNT_RESUMED",
            "PAPER_FUNDING_SETTLED",
            "PAPER_ORDER_ACCEPTED",
            "PAPER_ORDER_CANCELLED",
            "PAPER_ORDER_REJECTED",
            "PAPER_ORDER_FILLED",
            "RECONCILIATION_COMPLETED"
    );

    private final AuditService auditService;

    @Override
    public String consumerName() {
        return CONSUMER_NAME;
    }

    @Override
    public boolean supports(String eventType) {
        return SUPPORTED_EVENT_TYPES.contains(eventType);
    }

    @Override
    public void handle(OutboxEventDO event) {
        if (!supports(event.getEventType())) {
            throw new IllegalArgumentException("Unsupported outbox event type " + event.getEventType());
        }
        auditService.append(new AuditRecord(
                event.getEventId(),
                event.getCorrelationId(),
                event.getEventType(),
                event.getAggregateType(),
                event.getAggregateId(),
                null,
                null,
                null,
                "outbox",
                "SUCCESS",
                null,
                null,
                new Date(event.getAvailableAtMs()),
                event.getPayloadJson()
        ));
    }
}
