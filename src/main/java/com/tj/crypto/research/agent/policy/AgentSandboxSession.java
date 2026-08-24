package com.tj.crypto.research.agent.policy;

import java.nio.file.Path;
import java.time.Instant;

/** 对外只暴露无密钥的会话状态；进程重启后会话不会恢复，因而默认失效。 */
public record AgentSandboxSession(
        String sessionId,
        AgentLevel level,
        String operator,
        Path sandboxRoot,
        Instant createdAt,
        Instant expiresAt,
        boolean revoked,
        int consumedToolCalls) {
}
