package com.tj.crypto.reliability.outbox;

import com.tj.crypto.observability.slo.SloName;
import com.tj.crypto.observability.slo.TradingSloService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Claim/lease publisher with bounded retries and consumer checkpoints. */
@Slf4j
@Component
public class OutboxPublisher {
    private static final int MAX_ATTEMPTS = 8;
    private final OutboxMapper mapper;
    private final OutboxDispatchService dispatchService;
    private final List<OutboxEventHandler> handlers;
    private final TradingSloService sloService;
    private final String workerId = "outbox-" + UUID.randomUUID();

    public OutboxPublisher(OutboxMapper mapper, OutboxDispatchService dispatchService,
                           List<OutboxEventHandler> handlers, TradingSloService sloService) {
        this.mapper = mapper;
        this.dispatchService = dispatchService;
        this.handlers = List.copyOf(handlers);
        this.sloService = sloService;
    }

    @Scheduled(fixedDelayString = "${crypto.outbox.poll-interval-ms:500}")
    public void publishBatch() {
        long now = System.currentTimeMillis();
        for (OutboxEventDO candidate : mapper.selectClaimable(now, 100)) {
            if (mapper.claim(candidate.getEventId(), workerId, now + 30_000, now) != 1) continue;
            candidate.setAttempts(candidate.getAttempts() + 1);
            long started = System.nanoTime();
            try {
                dispatch(candidate);
                if (mapper.markPublished(candidate.getEventId(), workerId,
                        System.currentTimeMillis()) != 1) {
                    throw new IllegalStateException("Outbox publish lease was lost");
                }
                sloService.record(SloName.OUTBOX_DELIVERY, true, elapsedMs(started));
            } catch (RuntimeException e) {
                sloService.record(SloName.OUTBOX_DELIVERY, false, elapsedMs(started));
                int attempts = candidate.getAttempts();
                String status = attempts >= MAX_ATTEMPTS ? "DEAD_LETTER" : "RETRY";
                long delay = Math.min(300_000L, 1_000L << Math.min(8, attempts));
                mapper.markFailed(candidate.getEventId(), workerId, status,
                        System.currentTimeMillis() + delay, truncate(e.getMessage()));
                log.error("Outbox publication failed: eventId={}, attempt={}",
                        candidate.getEventId(), attempts, e);
            }
        }
    }

    private long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private void dispatch(OutboxEventDO event) {
        boolean matched = false;
        for (OutboxEventHandler handler : handlers) {
            if (!handler.supports(event.getEventType())) continue;
            matched = true;
            dispatchService.dispatch(handler, event);
        }
        if (!matched) {
            throw new IllegalStateException("No outbox handler registered for event type "
                    + event.getEventType());
        }
    }

    private String truncate(String message) {
        if (message == null) return "unknown outbox handler failure";
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
