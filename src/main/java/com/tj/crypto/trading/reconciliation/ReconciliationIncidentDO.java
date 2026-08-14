package com.tj.crypto.trading.reconciliation;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ReconciliationIncidentDO {
    private String incidentId;
    private String accountId;
    private String incidentType;
    private String severity;
    private String aggregateType;
    private String aggregateId;
    private String expectedJson;
    private String actualJson;
    private String status;
    private Long detectedAtMs;
    private Long resolvedAtMs;
    private String resolution;
    private String resolvedBy;
    private String fingerprint;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
