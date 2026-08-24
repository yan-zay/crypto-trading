package com.tj.crypto.autonomy.agent;

/**
 * 外部可信授权边界。
 *
 * <p>实现必须独立读取并验证签名制品、operator 与 Agent 身份、政策的完整规范化内容、
 * 日损失 context 对应的可信账本事实、撤销状态和全部绑定，并按 tokenId/useId 原子消费。
 * 仅回显 {@link AuthorizationReference} 永远不是可信实现。
 */
public interface TrustedAuthorizationVerifier {

    String L3_AUDIENCE = "crypto-trading:agent-l3:live-advice:v1";
    String L4_AUDIENCE = "crypto-trading:agent-l4:bounded-autonomy:v1";

    VerifiedAuthorization verifyAndConsume(AuthorizationVerificationRequest request);

    /**
     * 在本地消费 L4 capability 前重新认证当前调用主体，并从可信确定性内核核对完整
     * {@link CapabilityUseRequest} 的 command、valuation、risk 和 decision-envelope 摘要。
     * 未实现时必须拒绝，bearer token 本身不代表身份或执行候选真实性。
     */
    default VerifiedCapabilityConsumer verifyCapabilityConsumer(
            CapabilityConsumerVerificationRequest request) {
        throw new AgentAutonomyViolationException(
                "CAPABILITY_CONSUMER_VERIFIER_NOT_WIRED",
                "Trusted capability consumer identity verification is not wired");
    }
}
