package com.tj.crypto.research.agent.policy;

/**
 * 独立审批信任边界。
 *
 * <p>实现必须从受信任的不可变审批存储读取制品，验证签名、签名人身份、受众、目标、
 * 已认证请求主体、完整请求摘要和有效期，并以显式绑定请求主体的 {@code useId}
 * 原子地一次性消费审批。不得仅回显调用方提交的
 * {@link AgentApprovalReference} 字段。未配置可信实现时，L2 必须拒绝。
 */
public interface AgentApprovalVerifier {

    String PAPER_SHADOW_AUDIENCE = "crypto-trading:agent-l2:paper-shadow:v1";

    VerifiedAgentApproval verifyAndConsume(AgentApprovalVerificationRequest request);
}
