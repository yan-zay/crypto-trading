package com.tj.crypto.research.agent.sandbox;

import com.tj.crypto.research.agent.policy.AgentApprovalReference;
import com.tj.crypto.research.agent.policy.AgentArtifactVersions;
import com.tj.crypto.research.agent.policy.AgentProposalTarget;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** L2 的唯一写制品：只含 paper/shadow 评估意图，不包含可发送订单。 */
public record PaperShadowProposalRequest(
        AgentProposalTarget target,
        String hypothesisId,
        String experimentPlanId,
        String strategyArtifactId,
        List<String> instrumentScope,
        String objective,
        Duration ttl,
        AgentApprovalReference approval,
        AgentArtifactVersions versions) {

    public PaperShadowProposalRequest {
        if (target == null) throw new IllegalArgumentException("target is required");
        hypothesisId = SandboxValues.text(hypothesisId, "hypothesisId");
        experimentPlanId = SandboxValues.text(experimentPlanId, "experimentPlanId");
        strategyArtifactId = SandboxValues.text(strategyArtifactId, "strategyArtifactId");
        instrumentScope = SandboxValues.texts(instrumentScope, "instrumentScope");
        objective = SandboxValues.text(objective, "objective");
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        if (approval == null) throw new IllegalArgumentException("approval is required");
        if (versions == null) throw new IllegalArgumentException("versions is required");
    }

    /**
     * 审批系统必须签署这一不含审批本身、但绑定已认证请求主体的确定性负载摘要。
     *
     * <p>主体必须来自沙盒会话授权结果，不能从调用方自报字段推断。
     */
    public String approvalPayloadSha256(String requestingOperator) {
        String boundOperator = SandboxValues.text(requestingOperator, "requestingOperator");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "agent-paper-shadow-approval-payload-v2");
            update(digest, boundOperator);
            update(digest, target.name());
            update(digest, hypothesisId);
            update(digest, experimentPlanId);
            update(digest, strategyArtifactId);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(instrumentScope.size()).array());
            instrumentScope.forEach(value -> update(digest, value));
            update(digest, objective);
            update(digest, ttl.toString());
            update(digest, versions.gitCommit());
            update(digest, versions.codeArtifactSha256());
            update(digest, versions.containerImageDigest());
            update(digest, versions.dataManifestId());
            update(digest, versions.dataManifestSha256());
            update(digest, versions.configurationVersion());
            update(digest, versions.strategyArtifactVersion());
            update(digest, versions.agentRuntimeVersion());
            update(digest, versions.modelVersion());
            update(digest, versions.promptVersion());
            update(digest, versions.policyVersion());
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(versions.randomSeed()).array());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * 兼容旧调用点，但禁止生成未绑定请求主体的可用审批摘要。
     *
     * @deprecated 使用 {@link #approvalPayloadSha256(String)} 并传入已认证的 session operator
     */
    @Deprecated(forRemoval = false)
    public String approvalPayloadSha256() {
        throw new IllegalStateException(
                "requestingOperator is required for an approval payload; unbound approvals fail closed");
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
