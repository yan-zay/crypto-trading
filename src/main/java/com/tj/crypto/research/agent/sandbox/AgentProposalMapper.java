package com.tj.crypto.research.agent.sandbox;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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

    @Select("""
            SELECT p.* FROM agent_paper_shadow_proposal p
            LEFT JOIN agent_paper_shadow_proposal_revocation r
              ON r.proposal_id=p.proposal_id
            LEFT JOIN agent_paper_shadow_proposal_use u
              ON u.proposal_id=p.proposal_id
            WHERE p.proposal_id=#{proposalId} AND p.status='SUBMITTED'
              AND p.expires_at_ms>#{now} AND r.proposal_id IS NULL
              AND u.proposal_id IS NULL
            FOR UPDATE
            """)
    AgentProposalDO selectConsumable(@Param("proposalId") String proposalId,
                                     @Param("now") long now);

    @Insert("""
            INSERT INTO agent_paper_shadow_proposal_use
                (proposal_id, proposal_use_sha256, target_environment, strategy_name,
                 strategy_artifact_sha256, release_assignment_id, instrument_scope_sha256,
                 governance_subject_sha256, activated_by, activated_at_ms, expires_at_ms,
                 live_execution_authorized)
            VALUES (#{proposalId}, #{proposalUseSha256}, #{targetEnvironment}, #{strategyName},
                    #{strategyArtifactSha256}, #{releaseAssignmentId}, #{instrumentScopeSha256},
                    #{governanceSubjectSha256}, #{activatedBy}, #{activatedAtMs}, #{expiresAtMs}, 0)
            """)
    int insertEvaluationUse(AgentProposalEvaluationUseDO use);

    @Select("""
            SELECT u.*, p.proposal_json
            FROM agent_paper_shadow_proposal_use u
            JOIN agent_paper_shadow_proposal p ON p.proposal_id=u.proposal_id
            LEFT JOIN agent_paper_shadow_proposal_revocation r ON r.proposal_id=p.proposal_id
            WHERE u.expires_at_ms>#{now} AND r.proposal_id IS NULL
            ORDER BY u.activated_at_ms, u.proposal_id
            """)
    java.util.List<AgentProposalEvaluationUseDO> selectActiveEvaluationUses(
            @Param("now") long now);
}
