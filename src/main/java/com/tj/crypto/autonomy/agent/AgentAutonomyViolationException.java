package com.tj.crypto.autonomy.agent;

/** 确定性自治边界拒绝；调用方不得将该异常降级为允许。 */
public final class AgentAutonomyViolationException extends IllegalStateException {

    private final String reasonCode;

    public AgentAutonomyViolationException(String reasonCode, String message) {
        super(message);
        this.reasonCode = AutonomyValues.text(reasonCode, "reasonCode");
    }

    public String reasonCode() {
        return reasonCode;
    }
}
