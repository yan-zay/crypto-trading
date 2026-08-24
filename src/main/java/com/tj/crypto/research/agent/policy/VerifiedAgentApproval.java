package com.tj.crypto.research.agent.policy;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * 可信 verifier 对签名制品和一次性消费操作的回执。
 *
 * <p>该回执仍由调用服务逐字段比对，防止 verifier 配置错误或返回了另一份审批。
 */
public record VerifiedAgentApproval(
        String approvalId,
        String approvalArtifactSha256,
        String approvedPayloadSha256,
        String authenticatedSignerIdentity,
        String audience,
        String requestingOperator,
        AgentProposalTarget approvedTarget,
        String consumedUseId,
        Instant artifactExpiresAt,
        String verificationReceiptSha256) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final String UNBOUND_REQUESTING_OPERATOR = "<unbound-requesting-operator>";

    public VerifiedAgentApproval {
        approvalId = requireText(approvalId, "approvalId");
        approvalArtifactSha256 = digest(approvalArtifactSha256, "approvalArtifactSha256");
        approvedPayloadSha256 = digest(approvedPayloadSha256, "approvedPayloadSha256");
        authenticatedSignerIdentity = requireText(authenticatedSignerIdentity, "authenticatedSignerIdentity");
        audience = requireText(audience, "audience");
        requestingOperator = requireText(requestingOperator, "requestingOperator");
        if (approvedTarget == null || artifactExpiresAt == null) {
            throw new IllegalArgumentException("approvedTarget and artifactExpiresAt are required");
        }
        consumedUseId = requireText(consumedUseId, "consumedUseId");
        verificationReceiptSha256 = digest(verificationReceiptSha256, "verificationReceiptSha256");
    }

    /** 旧 verifier 的兼容入口；未提供 operator 的回执会在精确匹配时 fail-closed。 */
    @Deprecated(forRemoval = false)
    public VerifiedAgentApproval(
            String approvalId,
            String approvalArtifactSha256,
            String approvedPayloadSha256,
            String authenticatedSignerIdentity,
            String audience,
            AgentProposalTarget approvedTarget,
            String consumedUseId,
            Instant artifactExpiresAt,
            String verificationReceiptSha256) {
        this(approvalId, approvalArtifactSha256, approvedPayloadSha256,
                authenticatedSignerIdentity, audience, UNBOUND_REQUESTING_OPERATOR,
                approvedTarget, consumedUseId, artifactExpiresAt, verificationReceiptSha256);
    }

    public void requireExactMatch(AgentApprovalVerificationRequest request) {
        if (request == null) {
            throw new AgentPolicyViolationException(
                    "APPROVAL_VERIFICATION_FAILED", "Approval verification request is missing");
        }
        AgentApprovalReference reference = request.claimedReference();
        if (!approvalId.equals(reference.approvalId())
                || !approvalArtifactSha256.equals(reference.approvalArtifactSha256())) {
            throw violation("APPROVAL_ARTIFACT_MISMATCH",
                    "Verified approval artifact does not match the requested immutable reference");
        }
        if (!approvedPayloadSha256.equals(request.requestPayloadSha256())
                || !approvedPayloadSha256.equals(reference.approvedPayloadSha256())) {
            throw violation("APPROVAL_PAYLOAD_MISMATCH",
                    "Verified approval does not bind the exact proposal payload");
        }
        if (!authenticatedSignerIdentity.equals(reference.approvedBy())) {
            throw violation("APPROVAL_IDENTITY_MISMATCH",
                    "Authenticated signer identity does not match the approval reference");
        }
        if (!audience.equals(request.audience())) {
            throw violation("APPROVAL_AUDIENCE_MISMATCH",
                    "Verified approval audience does not match the L2 paper/shadow service");
        }
        if (UNBOUND_REQUESTING_OPERATOR.equals(requestingOperator)
                || !requestingOperator.equals(request.requestingOperator())) {
            throw violation("APPROVAL_OPERATOR_MISMATCH",
                    "Verified approval does not bind the authenticated requesting operator");
        }
        if (approvedTarget != request.target()) {
            throw violation("APPROVAL_SCOPE_MISMATCH",
                    "Verified approval target does not match the proposal target");
        }
        if (!consumedUseId.equals(request.useId())) {
            throw violation("APPROVAL_SINGLE_USE_MISMATCH",
                    "Approval was not consumed for the exact proposal use identifier");
        }
        if (!artifactExpiresAt.equals(reference.expiresAt())
                || request.proposalExpiresAt().isAfter(artifactExpiresAt)) {
            throw violation("APPROVAL_EXPIRED",
                    "Verified approval is not valid for the complete proposal lifetime");
        }
    }

    private static String digest(String value, String name) {
        String normalized = requireText(value, name).toLowerCase();
        if (!SHA256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 digest");
        }
        return normalized;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static AgentPolicyViolationException violation(String code, String message) {
        return new AgentPolicyViolationException(code, message);
    }
}
