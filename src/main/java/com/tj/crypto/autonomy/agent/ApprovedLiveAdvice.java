package com.tj.crypto.autonomy.agent;

import java.time.Instant;

/**
 * 经独立人类审批的 L3 建议回执，仍然不是订单、指令或可供 gateway 消费的能力令牌。
 */
public final class ApprovedLiveAdvice {

    private final TradeProposal proposal;
    private final String requestingOperator;
    private final String humanApprover;
    private final String approvalVerificationReceiptSha256;
    private final Instant approvedAt;
    private final Instant expiresAt;

    ApprovedLiveAdvice(
            TradeProposal proposal,
            String requestingOperator,
            String humanApprover,
            String approvalVerificationReceiptSha256,
            Instant approvedAt,
            Instant expiresAt) {
        if (proposal == null) throw new IllegalArgumentException("proposal is required");
        this.proposal = proposal;
        this.requestingOperator = AutonomyValues.text(requestingOperator, "requestingOperator");
        this.humanApprover = AutonomyValues.text(humanApprover, "humanApprover");
        this.approvalVerificationReceiptSha256 = AutonomyValues.digest(
                approvalVerificationReceiptSha256, "approvalVerificationReceiptSha256");
        if (approvedAt == null || expiresAt == null || !expiresAt.isAfter(approvedAt)) {
            throw new IllegalArgumentException("approved advice must have a positive remaining lifetime");
        }
        this.approvedAt = approvedAt;
        this.expiresAt = expiresAt;
    }

    public TradeProposal proposal() { return proposal; }
    public String requestingOperator() { return requestingOperator; }
    public String humanApprover() { return humanApprover; }
    public String approvalVerificationReceiptSha256() { return approvalVerificationReceiptSha256; }
    public Instant approvedAt() { return approvedAt; }
    public Instant expiresAt() { return expiresAt; }
}
