package com.tj.crypto.research.agent.policy;

import java.time.Instant;
import java.util.regex.Pattern;

/** 独立人工审批制品的不可变引用；Agent 不能创建或修改审批。 */
public record AgentApprovalReference(
        String approvalId,
        String approvalArtifactSha256,
        String approvedPayloadSha256,
        String approvedBy,
        Instant approvedAt,
        Instant expiresAt,
        AgentProposalTarget approvedTarget) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

    public AgentApprovalReference {
        approvalId = requireText(approvalId, "approvalId");
        approvalArtifactSha256 = requireText(approvalArtifactSha256, "approvalArtifactSha256");
        if (!SHA256.matcher(approvalArtifactSha256).matches()) {
            throw new IllegalArgumentException("approvalArtifactSha256 must be a SHA-256 digest");
        }
        approvalArtifactSha256 = approvalArtifactSha256.toLowerCase();
        approvedPayloadSha256 = requireText(approvedPayloadSha256, "approvedPayloadSha256");
        if (!SHA256.matcher(approvedPayloadSha256).matches()) {
            throw new IllegalArgumentException("approvedPayloadSha256 must be a SHA-256 digest");
        }
        approvedPayloadSha256 = approvedPayloadSha256.toLowerCase();
        approvedBy = requireText(approvedBy, "approvedBy");
        if (approvedAt == null || expiresAt == null || !expiresAt.isAfter(approvedAt)) {
            throw new IllegalArgumentException("Approval timestamps must define a positive validity window");
        }
        if (approvedTarget == null) throw new IllegalArgumentException("approvedTarget is required");
    }

    public void validateFor(String agentOperator, AgentProposalTarget target, String payloadSha256,
                            Instant proposalCreatedAt, Instant proposalExpiresAt) {
        if (approvedBy.equalsIgnoreCase(requireText(agentOperator, "agentOperator"))) {
            throw new AgentPolicyViolationException(
                    "SELF_APPROVAL_FORBIDDEN", "Agent operator cannot approve its own proposal");
        }
        if (approvedTarget != target) {
            throw new AgentPolicyViolationException(
                    "APPROVAL_SCOPE_MISMATCH", "Approval target does not match proposal target");
        }
        if (!approvedPayloadSha256.equals(requireText(payloadSha256, "payloadSha256").toLowerCase())) {
            throw new AgentPolicyViolationException(
                    "APPROVAL_PAYLOAD_MISMATCH", "Approval does not bind the exact proposal payload");
        }
        if (approvedAt.isAfter(proposalCreatedAt) || !expiresAt.isAfter(proposalCreatedAt)
                || proposalExpiresAt.isAfter(expiresAt)) {
            throw new AgentPolicyViolationException(
                    "APPROVAL_EXPIRED", "Approval is not valid for the complete proposal lifetime");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
