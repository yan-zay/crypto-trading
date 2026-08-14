package com.tj.crypto.admin.application;

import java.util.Date;

/** Structured, secret-free audit input. */
public record AuditRecord(
        String requestId,
        String correlationId,
        String operationType,
        String resourceType,
        String resourceId,
        String configType,
        String configKey,
        String versionId,
        String operator,
        String outcome,
        String sourceIp,
        Long latencyMs,
        Date operationTime,
        String detail
) {
    public AuditRecord {
        if (operationType == null || operationType.isBlank()) {
            throw new IllegalArgumentException("operationType is required");
        }
        if (operator == null || operator.isBlank()) operator = "system";
        if (outcome == null || outcome.isBlank()) outcome = "SUCCESS";
        if (operationTime == null) operationTime = new Date();
    }
}
