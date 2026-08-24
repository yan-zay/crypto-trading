package com.tj.crypto.research.agent.sandbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
@RequiredArgsConstructor
public class PersistentAgentProposalRepository implements AgentProposalRepository {
    private final AgentProposalMapper mapper;
    private final ObjectMapper objectMapper;

    @Override
    public void save(PaperShadowProposal proposal) {
        AgentProposalDO target = new AgentProposalDO();
        target.setProposalId(proposal.proposalId());
        target.setSessionId(proposal.sessionId());
        target.setTargetEnvironment(proposal.target().name());
        target.setApprovalReference(proposal.approval().approvalId());
        target.setApprovalArtifactSha256(proposal.approval().approvalArtifactSha256());
        target.setApprovalVerificationReceiptSha256(proposal.approvalVerificationReceiptSha256());
        target.setApprovedPayloadSha256(proposal.approval().approvedPayloadSha256());
        target.setPolicyVersion(proposal.versions().policyVersion());
        target.setCreatedAtMs(proposal.createdAt().toEpochMilli());
        target.setExpiresAtMs(proposal.expiresAt().toEpochMilli());
        target.setStatus(proposal.status().name());
        try {
            target.setProposalJson(objectMapper.writeValueAsString(proposal));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Proposal cannot be serialized", e);
        }
        if (mapper.insert(target) != 1) throw new IllegalStateException("Agent proposal insert failed");
    }

    @Override
    public void revoke(String proposalId, String operator, String reason, Instant revokedAt) {
        if (mapper.revoke(proposalId, operator, reason, revokedAt.toEpochMilli()) != 1) {
            throw new IllegalStateException("Proposal is missing or is no longer revocable");
        }
    }
}
