package com.tj.crypto.research.agent;

import java.time.Instant;
import java.util.List;

/**
 * L0 Research Agent 的统一响应信封。
 *
 * <p>每个结果都携带来源、生成时间和限制，避免把运行时摘要误当成交易建议或生产证据。
 */
public record ResearchAgentEnvelope(
        String agentLevel,
        boolean modelConnected,
        String operation,
        boolean readOnly,
        boolean deterministic,
        Instant generatedAt,
        List<String> dataSources,
        List<String> limitations,
        Object result) {
}
