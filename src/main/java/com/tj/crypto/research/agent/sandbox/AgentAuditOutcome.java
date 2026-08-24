package com.tj.crypto.research.agent.sandbox;

/** 追加式调用审计阶段。 */
public enum AgentAuditOutcome {
    STARTED,
    SUCCEEDED,
    DENIED,
    TIMED_OUT,
    FAILED,
    REVOKED
}
