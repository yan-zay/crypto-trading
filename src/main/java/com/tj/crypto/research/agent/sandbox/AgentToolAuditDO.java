package com.tj.crypto.research.agent.sandbox;

import lombok.Data;

/** agent_tool_audit 追加式表映射。 */
@Data
public class AgentToolAuditDO {
    private Long id;
    private String eventId;
    private String callId;
    private String sessionId;
    private String agentLevel;
    private String operation;
    private String outcome;
    private String operator;
    private String policyVersion;
    private String inputSha256;
    private String approvalReference;
    private String modelVersion;
    private String promptVersion;
    private String modelOutputSha256;
    private String evalReportSha256;
    private Long occurredAtMs;
    private Long elapsedMs;
    private String reasonCode;
    private String resultReference;
    private String detail;
}
