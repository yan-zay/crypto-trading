package com.tj.crypto.research.agent.policy;

/** 未配置外部可信审批验证器时使用的 fail-closed 实现。 */
public final class RejectingAgentApprovalVerifier implements AgentApprovalVerifier {
    @Override
    public VerifiedAgentApproval verifyAndConsume(AgentApprovalVerificationRequest request) {
        throw new AgentPolicyViolationException(
                "APPROVAL_VERIFIER_UNAVAILABLE",
                "A trusted signature and identity approval verifier is not configured");
    }
}
