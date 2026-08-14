package com.tj.crypto.reliability.outbox;

import com.tj.crypto.observability.slo.SloName;
import com.tj.crypto.observability.slo.TradingSloService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPublisherTest {

    @Test
    void failsClosedWhenNoHandlerMatchesEvent() {
        OutboxMapper mapper = mock(OutboxMapper.class);
        OutboxDispatchService dispatchService = mock(OutboxDispatchService.class);
        TradingSloService sloService = mock(TradingSloService.class);
        OutboxEventHandler unrelatedHandler = mock(OutboxEventHandler.class);
        OutboxEventDO event = event("event-1", "UNREGISTERED_EVENT");
        when(mapper.selectClaimable(anyLong(), eq(100))).thenReturn(List.of(event));
        when(mapper.claim(eq("event-1"), anyString(), anyLong(), anyLong())).thenReturn(1);
        when(unrelatedHandler.supports("UNREGISTERED_EVENT")).thenReturn(false);

        new OutboxPublisher(mapper, dispatchService, List.of(unrelatedHandler), sloService)
                .publishBatch();

        verify(dispatchService, never()).dispatch(eq(unrelatedHandler), eq(event));
        verify(mapper, never()).markPublished(eq("event-1"), anyString(), anyLong());
        verify(mapper).markFailed(eq("event-1"), anyString(), eq("RETRY"), anyLong(),
                contains("No outbox handler registered"));
        verify(sloService).record(eq(SloName.OUTBOX_DELIVERY), eq(false), anyLong());
    }

    @Test
    void publishesOnlyAfterMatchingHandlerSucceeds() {
        OutboxMapper mapper = mock(OutboxMapper.class);
        OutboxDispatchService dispatchService = mock(OutboxDispatchService.class);
        TradingSloService sloService = mock(TradingSloService.class);
        OutboxEventHandler handler = mock(OutboxEventHandler.class);
        OutboxEventDO event = event("event-2", "PAPER_ORDER_FILLED");
        when(mapper.selectClaimable(anyLong(), eq(100))).thenReturn(List.of(event));
        when(mapper.claim(eq("event-2"), anyString(), anyLong(), anyLong())).thenReturn(1);
        when(mapper.markPublished(eq("event-2"), anyString(), anyLong())).thenReturn(1);
        when(handler.supports("PAPER_ORDER_FILLED")).thenReturn(true);

        new OutboxPublisher(mapper, dispatchService, List.of(handler), sloService).publishBatch();

        verify(dispatchService).dispatch(handler, event);
        verify(mapper).markPublished(eq("event-2"), anyString(), anyLong());
        verify(mapper, never()).markFailed(eq("event-2"), anyString(), anyString(), anyLong(), anyString());
        verify(sloService).record(eq(SloName.OUTBOX_DELIVERY), eq(true), anyLong());
    }

    @Test
    void retriesFailedHandlerAndPublishesOnlyAfterRecovery() {
        OutboxMapper mapper = mock(OutboxMapper.class);
        OutboxDispatchService dispatchService = mock(OutboxDispatchService.class);
        TradingSloService sloService = mock(TradingSloService.class);
        OutboxEventHandler handler = mock(OutboxEventHandler.class);
        OutboxEventDO event = event("event-3", "DATASET_EXPORT_COMPLETED");
        when(mapper.selectClaimable(anyLong(), eq(100))).thenReturn(List.of(event));
        when(mapper.claim(eq("event-3"), anyString(), anyLong(), anyLong())).thenReturn(1);
        when(mapper.markPublished(eq("event-3"), anyString(), anyLong())).thenReturn(1);
        when(handler.supports("DATASET_EXPORT_COMPLETED")).thenReturn(true);
        doThrow(new IllegalStateException("consumer unavailable"))
                .doNothing()
                .when(dispatchService).dispatch(handler, event);
        doNothing().when(handler).handle(event);

        OutboxPublisher publisher = new OutboxPublisher(mapper, dispatchService,
                List.of(handler), sloService);
        publisher.publishBatch();
        publisher.publishBatch();

        verify(dispatchService, times(2)).dispatch(handler, event);
        verify(mapper).markFailed(eq("event-3"), anyString(), eq("RETRY"), anyLong(),
                eq("consumer unavailable"));
        verify(mapper, times(1)).markPublished(eq("event-3"), anyString(), anyLong());
        verify(sloService).record(eq(SloName.OUTBOX_DELIVERY), eq(false), anyLong());
        verify(sloService).record(eq(SloName.OUTBOX_DELIVERY), eq(true), anyLong());
    }

    private OutboxEventDO event(String eventId, String eventType) {
        OutboxEventDO event = new OutboxEventDO();
        event.setEventId(eventId);
        event.setEventType(eventType);
        event.setAttempts(0);
        return event;
    }
}
