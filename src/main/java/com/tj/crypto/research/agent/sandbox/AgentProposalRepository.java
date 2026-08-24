package com.tj.crypto.research.agent.sandbox;

import java.time.Instant;

/** L2 提案存储；没有执行、发布或交易方法。 */
public interface AgentProposalRepository {
    void save(PaperShadowProposal proposal);

    void revoke(String proposalId, String operator, String reason, Instant revokedAt);
}
