package com.tj.crypto.autonomy.agent;

import java.time.Instant;

/** 未接持久撤销存储时默认拒绝所有读写，不能将“未知”解释为“未撤销”。 */
public final class RejectingProposalRevocationStore implements ProposalRevocationStore {
    @Override
    public ProposalRevocation revoke(String proposalId, String operator, String reason, Instant revokedAt) {
        throw unavailable();
    }

    @Override
    public boolean isRevoked(String proposalId) {
        throw unavailable();
    }

    private static AgentAutonomyViolationException unavailable() {
        return new AgentAutonomyViolationException(
                "PROPOSAL_REVOCATION_STORE_NOT_WIRED",
                "A cross-node persistent proposal revocation store is not wired");
    }
}
