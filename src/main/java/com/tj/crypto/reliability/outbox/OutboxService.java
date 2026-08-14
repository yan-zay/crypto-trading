package com.tj.crypto.reliability.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Appends durable events inside the caller's database transaction. */
@Service
@RequiredArgsConstructor
public class OutboxService {
    private final OutboxMapper mapper;
    private final ObjectMapper objectMapper;

    public String append(String aggregateType, String aggregateId, String eventType,
                         Object payload, String correlationId, long eventTime) {
        OutboxEventDO event = new OutboxEventDO();
        event.setEventId(UUID.randomUUID().toString());
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setEventType(eventType);
        event.setPayloadJson(toJson(payload));
        event.setCorrelationId(correlationId);
        event.setStatus("PENDING");
        event.setAttempts(0);
        event.setAvailableAtMs(eventTime);
        mapper.insert(event);
        return event.getEventId();
    }

    public OutboxBacklog backlog(long now) {
        long count = mapper.countBacklog();
        Long oldest = mapper.oldestPendingAt();
        return new OutboxBacklog(count, oldest == null ? 0 : Math.max(0, now - oldest));
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Outbox payload is not serializable", e);
        }
    }

    public record OutboxBacklog(long pendingEvents, long oldestAgeMs) {}
}
