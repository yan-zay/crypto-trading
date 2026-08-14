package com.tj.crypto.research.agent;

import java.util.List;

/** 无模型推断、无写操作的确定性运行时研究摘要。 */
public record RuntimeResearchSummary(
        int registeredStrategyCount,
        int enabledStrategyCount,
        int registeredFactorCount,
        List<String> enabledStrategies,
        List<String> registeredFactors,
        String conclusionScope) {
}
