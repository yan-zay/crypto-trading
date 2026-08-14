package com.tj.crypto.reliability.outbox;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxDispatchServiceTest {

    @Test
    void persistsCheckpointAfterHandlerCompletes() {
        ProcessedEventMapper mapper = mock(ProcessedEventMapper.class);
        OutboxEventHandler handler = mock(OutboxEventHandler.class);
        OutboxEventDO event = event();
        when(handler.consumerName()).thenReturn("consumer-v1");
        when(mapper.exists("consumer-v1", "event-1")).thenReturn(0);
        when(mapper.markProcessed(org.mockito.ArgumentMatchers.eq("consumer-v1"),
                org.mockito.ArgumentMatchers.eq("event-1"), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(1);

        new OutboxDispatchService(mapper).dispatch(handler, event);

        verify(handler).handle(event);
        verify(mapper).markProcessed(org.mockito.ArgumentMatchers.eq("consumer-v1"),
                org.mockito.ArgumentMatchers.eq("event-1"), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void skipsAlreadyCheckpointedEvent() {
        ProcessedEventMapper mapper = mock(ProcessedEventMapper.class);
        OutboxEventHandler handler = mock(OutboxEventHandler.class);
        OutboxEventDO event = event();
        when(handler.consumerName()).thenReturn("consumer-v1");
        when(mapper.exists("consumer-v1", "event-1")).thenReturn(1);

        new OutboxDispatchService(mapper).dispatch(handler, event);

        verify(handler, never()).handle(event);
        verify(mapper, never()).markProcessed(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void doesNotCheckpointFailedHandler() {
        ProcessedEventMapper mapper = mock(ProcessedEventMapper.class);
        OutboxEventHandler handler = mock(OutboxEventHandler.class);
        OutboxEventDO event = event();
        when(handler.consumerName()).thenReturn("consumer-v1");
        when(mapper.exists("consumer-v1", "event-1")).thenReturn(0);
        doThrow(new IllegalStateException("projection failed")).when(handler).handle(event);

        OutboxDispatchService service = new OutboxDispatchService(mapper);

        assertThatThrownBy(() -> service.dispatch(handler, event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("projection failed");
        verify(mapper, never()).markProcessed(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    private OutboxEventDO event() {
        OutboxEventDO event = new OutboxEventDO();
        event.setEventId("event-1");
        return event;
    }
}
