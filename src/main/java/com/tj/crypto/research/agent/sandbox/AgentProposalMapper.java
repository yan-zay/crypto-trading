package com.tj.crypto.research.agent.sandbox;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentProposalMapper {
    @Insert("""
            INSERT INTO agent_paper_shadow_proposal
                (proposal_id, session_id, target_environment, approval_reference,
                 approval_artifact_sha256, approval_verification_receipt_sha256,
                 policy_version, created_at_ms, expires_at_ms,
                 approved_payload_sha256, status, proposal_json)
            VALUES
                (#{proposalId}, #{sessionId}, #{targetEnvironment}, #{approvalReference},
                 #{approvalArtifactSha256}, #{approvalVerificationReceiptSha256},
                 #{policyVersion}, #{createdAtMs}, #{expiresAtMs},
                 #{approvedPayloadSha256}, #{status}, CAST(#{proposalJson} AS JSON))
            """)
    int insert(AgentProposalDO proposal);

    @Insert("""
            INSERT INTO agent_paper_shadow_proposal_revocation
                (proposal_id, revoked_by, revoke_reason, revoked_at_ms)
            SELECT proposal_id, #{operator}, #{reason}, #{revokedAtMs}
              FROM agent_paper_shadow_proposal
             WHERE proposal_id = #{proposalId}
            """)
    int revoke(@Param("proposalId") String proposalId,
               @Param("operator") String operator,
               @Param("reason") String reason,
               @Param("revokedAtMs") long revokedAtMs);
}
