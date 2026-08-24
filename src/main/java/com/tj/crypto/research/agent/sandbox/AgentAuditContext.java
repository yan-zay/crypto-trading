package com.tj.crypto.research.agent.sandbox;

import java.util.regex.Pattern;

/** 调用审计只保存不可逆摘要和版本引用，不保存 prompt、上下文正文或 secret。 */
public record AgentAuditContext(
        String inputSha256,
        String approvalReference,
        String modelVersion,
        String promptVersion) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

    public AgentAuditContext {
        if (inputSha256 != null && !SHA256.matcher(inputSha256).matches()) {
            throw new IllegalArgumentException("inputSha256 must be a SHA-256 digest");
        }
        inputSha256 = inputSha256 == null ? null : inputSha256.toLowerCase();
        approvalReference = optional(approvalReference, 100);
        modelVersion = optional(modelVersion, 100);
        promptVersion = optional(promptVersion, 100);
    }

    public static AgentAuditContext empty() {
        return new AgentAuditContext(null, null, null, null);
    }

    private static String optional(String value, int max) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > max) {
            throw new IllegalArgumentException("Audit context value is blank or too long");
        }
        return normalized;
    }
}
