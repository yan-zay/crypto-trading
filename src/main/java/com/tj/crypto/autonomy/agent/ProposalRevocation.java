package com.tj.crypto.autonomy.agent;

import java.time.Instant;

/** 持久化提案撤销事实。 */
public record ProposalRevocation(
        String proposalId,
        String operator,
        String reason,
        Instant revokedAt) {
    public ProposalRevocation {
        proposalId = AutonomyValues.text(proposalId, "proposalId");
        operator = AutonomyValues.text(operator, "operator");
        reason = AutonomyValues.text(reason, "reason");
        if (revokedAt == null) throw new IllegalArgumentException("revokedAt is required");
    }
}
