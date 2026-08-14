package com.tj.crypto.reliability.outbox;

import com.tj.crypto.admin.application.AuditRecord;
import com.tj.crypto.admin.application.AuditService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BusinessEventAuditOutboxHandlerTest {

    @Test
    void projectsSupportedBusinessEventIntoDurableAuditRecord() {
        AuditService auditService = mock(AuditService.class);
        BusinessEventAuditOutboxHandler handler = new BusinessEventAuditOutboxHandler(auditService);
        OutboxEventDO event = new OutboxEventDO();
        event.setEventId("event-1");
        event.setCorrelationId("correlation-1");
        event.setAggregateType("OMS_ORDER");
        event.setAggregateId("order-1");
        event.setEventType("PAPER_ORDER_FILLED");
        event.setAvailableAtMs(1_700_000_000_123L);
        event.setPayloadJson("{\"status\":\"FILLED\"}");

        handler.handle(event);

        ArgumentCaptor<AuditRecord> record = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditService).append(record.capture());
        assertThat(record.getValue().requestId()).isEqualTo("event-1");
        assertThat(record.getValue().correlationId()).isEqualTo("correlation-1");
        assertThat(record.getValue().operationType()).isEqualTo("PAPER_ORDER_FILLED");
        assertThat(record.getValue().resourceType()).isEqualTo("OMS_ORDER");
        assertThat(record.getValue().resourceId()).isEqualTo("order-1");
        assertThat(record.getValue().operator()).isEqualTo("outbox");
        assertThat(record.getValue().operationTime()).hasTime(1_700_000_000_123L);
        assertThat(record.getValue().detail()).isEqualTo("{\"status\":\"FILLED\"}");
    }

    @Test
    void advertisesOnlyExplicitlySupportedEventTypes() {
        BusinessEventAuditOutboxHandler handler =
                new BusinessEventAuditOutboxHandler(mock(AuditService.class));

        assertThat(handler.consumerName()).isEqualTo("business-event-audit-v1");
        assertThat(handler.supports("DATASET_EXPORT_COMPLETED")).isTrue();
        assertThat(handler.supports("PAPER_ACCOUNT_STARTED")).isTrue();
        assertThat(handler.supports("UNKNOWN_EVENT")).isFalse();
    }
}
