package com.tj.crypto.reliability.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomically applies an internal projection and its idempotency checkpoint. */
@Service
@RequiredArgsConstructor
public class OutboxDispatchService {
    private final ProcessedEventMapper processedEventMapper;

    @Transactional
    public void dispatch(OutboxEventHandler handler, OutboxEventDO event) {
        if (processedEventMapper.exists(handler.consumerName(), event.getEventId()) > 0) return;
        handler.handle(event);
        if (processedEventMapper.markProcessed(handler.consumerName(), event.getEventId(),
                System.currentTimeMillis()) != 1) {
            throw new IllegalStateException("Outbox checkpoint was concurrently inserted");
        }
    }
}
