package com.tj.crypto.research.agent.policy;

import java.time.Instant;
import java.util.regex.Pattern;

/** 传给独立审批验证器的完整预期边界；所有字段都必须由签名审批制品覆盖。 */
public record AgentApprovalVerificationRequest(
        String useId,
        String audience,
        AgentApprovalReference claimedReference,
        String requestingOperator,
        AgentProposalTarget target,
        String requestPayloadSha256,
        Instant proposalCreatedAt,
        Instant proposalExpiresAt) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public AgentApprovalVerificationRequest {
        useId = requireText(useId, "useId");
        audience = requireText(audience, "audience");
        requestingOperator = requireText(requestingOperator, "requestingOperator");
        if (claimedReference == null || target == null) {
            throw new IllegalArgumentException("claimedReference and target are required");
        }
        requestPayloadSha256 = requireText(requestPayloadSha256, "requestPayloadSha256").toLowerCase();
        if (!SHA256.matcher(requestPayloadSha256).matches()) {
            throw new IllegalArgumentException("requestPayloadSha256 must be a lowercase SHA-256 digest");
        }
        if (proposalCreatedAt == null || proposalExpiresAt == null
                || !proposalExpiresAt.isAfter(proposalCreatedAt)) {
            throw new IllegalArgumentException("Proposal timestamps must define a positive validity window");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
