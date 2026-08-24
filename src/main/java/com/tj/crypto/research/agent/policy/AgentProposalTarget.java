package com.tj.crypto.research.agent.policy;

/** L2 只能产生模拟盘或影子环境提案；类型系统中故意不提供 LIVE。 */
public enum AgentProposalTarget {
    PAPER,
    SHADOW
}
