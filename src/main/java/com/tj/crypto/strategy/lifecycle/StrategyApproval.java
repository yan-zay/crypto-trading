package com.tj.crypto.strategy.lifecycle;

import java.time.Instant;

public record StrategyApproval(
        String approvalId,
        String strategyName,
        String artifactSha256,
        long reviewCycle,
        ApprovalDecision decision,
        String approvalScopeSha256,
        String evidenceSetSha256,
        Instant expiresAt,
        String approvedBy,
        Instant approvedAt) {

    public boolean validAt(Instant now) {
        return decision == ApprovalDecision.APPROVE && !approvedAt.isAfter(now) && expiresAt.isAfter(now);
    }
}
