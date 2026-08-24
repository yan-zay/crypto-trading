package com.tj.crypto.research.agent.sandbox;

import com.tj.crypto.research.agent.policy.AgentApprovalReference;
import com.tj.crypto.research.agent.policy.AgentArtifactVersions;
import com.tj.crypto.research.agent.policy.AgentProposalTarget;

import java.time.Instant;
import java.util.List;

/** 持久化的 L2 提案；下游仍需独立 policy gate，本文档本身不能执行交易。 */
public record PaperShadowProposal(
        String proposalId,
        String sessionId,
        AgentProposalTarget target,
        String hypothesisId,
        String experimentPlanId,
        String strategyArtifactId,
        List<String> instrumentScope,
        String objective,
        AgentApprovalReference approval,
        String approvalVerificationReceiptSha256,
        AgentArtifactVersions versions,
        Instant createdAt,
        Instant expiresAt,
        ProposalStatus status) {

    public enum ProposalStatus {
        SUBMITTED,
        REVOKED,
        EXPIRED
    }
}
