package com.tj.crypto.research.agent.policy;

import java.nio.file.Path;
import java.time.Instant;

/** 一次成功授权的不可变快照，供审计和超时边界使用。 */
public record AgentAuthorization(
        String sessionId,
        AgentLevel level,
        String operator,
        AgentToolName tool,
        String policyVersion,
        Path sandboxRoot,
        Instant authorizedAt,
        Instant sessionExpiresAt) {
}
