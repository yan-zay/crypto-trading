package com.tj.crypto.autonomy.agent;

import java.time.Instant;

/** 跨节点持久的提案撤销 port。 */
public interface ProposalRevocationStore {

    ProposalRevocation revoke(String proposalId, String operator, String reason, Instant revokedAt);

    boolean isRevoked(String proposalId);
}
