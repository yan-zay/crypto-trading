package com.tj.crypto.research.agent.sandbox;

import com.tj.crypto.research.agent.policy.AgentLevel;

import java.time.Instant;

/** 不包含 prompt、secret 或业务 payload 的追加式 Agent 调用审计事件。 */
public record AgentToolAuditEvent(
        String eventId,
        String callId,
        String sessionId,
        AgentLevel agentLevel,
        String operation,
        AgentAuditOutcome outcome,
        String operator,
        String policyVersion,
        String inputSha256,
        String approvalReference,
        String modelVersion,
        String promptVersion,
        Instant occurredAt,
        Long elapsedMs,
        String reasonCode,
        String resultReference,
        String detail) {

    public AgentToolAuditEvent {
        eventId = text(eventId, "eventId", 64);
        callId = text(callId, "callId", 64);
        sessionId = text(sessionId, "sessionId", 64);
        if (agentLevel == null) throw new IllegalArgumentException("agentLevel is required");
        operation = text(operation, "operation", 80);
        if (outcome == null) throw new IllegalArgumentException("outcome is required");
        operator = text(operator, "operator", 100);
        policyVersion = text(policyVersion, "policyVersion", 100);
        inputSha256 = optional(inputSha256, 64);
        if (inputSha256 != null && !inputSha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("inputSha256 must be a SHA-256 digest");
        }
        approvalReference = optional(approvalReference, 100);
        modelVersion = optional(modelVersion, 100);
        promptVersion = optional(promptVersion, 100);
        if (occurredAt == null) throw new IllegalArgumentException("occurredAt is required");
        if (elapsedMs != null && elapsedMs < 0) throw new IllegalArgumentException("elapsedMs cannot be negative");
        reasonCode = optional(reasonCode, 100);
        resultReference = optional(resultReference, 100);
        detail = optional(detail, 1000);
    }

    private static String text(String value, String name, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        String normalized = value.trim();
        if (normalized.length() > max) throw new IllegalArgumentException(name + " is too long");
        return normalized;
    }

    private static String optional(String value, int max) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }
}
