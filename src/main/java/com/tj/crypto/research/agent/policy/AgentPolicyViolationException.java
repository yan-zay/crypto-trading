package com.tj.crypto.research.agent.policy;

/** 确定性策略拒绝；调用方不得把该异常降级为允许。 */
public class AgentPolicyViolationException extends IllegalStateException {

    private final String reasonCode;

    public AgentPolicyViolationException(String reasonCode, String message) {
        super(message);
        this.reasonCode = requireText(reasonCode, "reasonCode");
    }

    public String reasonCode() {
        return reasonCode;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
