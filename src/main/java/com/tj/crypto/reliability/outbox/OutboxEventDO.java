package com.tj.crypto.reliability.outbox;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OutboxEventDO {
    private String eventId;
    private Long eventSequence;
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    private String payloadJson;
    private String correlationId;
    private String status;
    private Integer attempts;
    private Long availableAtMs;
    private String claimedBy;
    private Long claimUntilMs;
    private Long publishedAtMs;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
