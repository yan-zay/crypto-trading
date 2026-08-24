package com.tj.crypto.research.agent.policy;

/**
 * Agent 可调用工具的封闭集合。枚举中不得加入订单、密钥、配置发布或 Kill Switch 工具。
 */
public enum AgentToolName {
    GENERATE_FALSIFIABLE_HYPOTHESIS,
    GENERATE_EXPERIMENT_PLAN,
    SUBMIT_PAPER_PROPOSAL,
    SUBMIT_SHADOW_PROPOSAL
}
