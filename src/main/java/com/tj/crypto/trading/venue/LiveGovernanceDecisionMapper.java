package com.tj.crypto.trading.venue;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
interface LiveGovernanceDecisionMapper {
    @Insert("""
            INSERT INTO live_governance_decision (
                decision_id, client_order_id, command_sha256, exchange, account_id, strategy_id,
                release_assignment_id, release_assignment_version, strategy_artifact_sha256,
                governance_subject_sha256, governance_scope, governance_environment, account_scope,
                git_commit, build_sha256, container_image_digest, configuration_sha256,
                dataset_manifest_sha256, key_reference, run_id, policy_version,
                evidence_set_sha256, evidence_references, approval_request_id, approval_action,
                approval_governance_subject_sha256, requested_by,
                authorized_at_ms, expires_at_ms
            ) VALUES (
                #{d.decisionId}, #{d.clientOrderId}, #{d.commandSha256}, #{d.exchange},
                #{d.accountId}, #{d.strategyId}, #{d.releaseAssignmentId},
                #{d.releaseAssignmentVersion}, #{d.strategyArtifactSha256},
                #{d.governanceSubjectSha256}, #{d.governanceScope}, #{d.governanceEnvironment},
                #{d.accountScope}, #{d.gitCommit}, #{d.buildSha256}, #{d.containerImageDigest},
                #{d.configurationSha256}, #{d.datasetManifestSha256}, #{d.keyReference},
                #{d.runId}, #{d.policyVersion}, #{d.evidenceSetSha256}, #{d.evidenceReferences},
                #{d.approvalRequestId}, 'LIVE_RISK_INCREASE', #{d.governanceSubjectSha256},
                #{d.requestedBy},
                #{d.authorizedAtMs}, #{d.expiresAtMs}
            )
            """)
    int insert(@Param("d") LiveGovernanceDecisionDO decision);

    @Insert("""
            INSERT INTO live_governance_decision_evidence
                (decision_id, control_kind, evidence_id)
            VALUES (#{decisionId}, #{controlKind}, #{evidenceId})
            """)
    int insertEvidence(@Param("decisionId") String decisionId,
                       @Param("controlKind") String controlKind,
                       @Param("evidenceId") String evidenceId);

    @Select("SELECT * FROM live_governance_decision WHERE decision_id=#{decisionId}")
    LiveGovernanceDecisionDO selectById(@Param("decisionId") String decisionId);
}
