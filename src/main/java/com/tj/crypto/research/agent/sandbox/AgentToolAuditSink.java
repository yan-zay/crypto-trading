package com.tj.crypto.research.agent.sandbox;

/** 审计写入失败必须向上抛出并阻止工具继续执行。 */
public interface AgentToolAuditSink {
    void append(AgentToolAuditEvent event);
}
