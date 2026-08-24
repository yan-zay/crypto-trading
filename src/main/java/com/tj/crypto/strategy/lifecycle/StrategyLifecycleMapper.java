package com.tj.crypto.strategy.lifecycle;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
interface StrategyLifecycleMapper {
    @Select("SELECT * FROM strategy_signing_key WHERE key_id=#{keyId}")
    TrustedSigningKeyRow selectSigningKey(@Param("keyId") String keyId);

    @Insert("""
            INSERT INTO strategy_artifact
                (artifact_sha256, strategy_name, artifact_version, artifact_content,
                 media_type, artifact_uri, signature_algorithm, signing_key_id,
                 signature_base64, signed_at_ms, expires_at_ms, submitted_by,
                 submitted_at_ms, research_registration_id, research_registration_sha256)
            VALUES
                (#{artifactSha256}, #{strategyName}, #{artifactVersion}, #{artifactContent},
                 #{mediaType}, #{artifactUri}, #{signatureAlgorithm}, #{signingKeyId},
                 #{signatureBase64}, #{signedAtMs}, #{expiresAtMs}, #{submittedBy},
                 #{submittedAtMs}, #{researchRegistrationId}, #{researchRegistrationSha256})
            """)
    int insertArtifact(StrategyArtifactRow row);

    @Select("SELECT * FROM strategy_artifact WHERE artifact_sha256=#{digest}")
    StrategyArtifactRow selectArtifact(@Param("digest") String digest);

    @Insert("""
            INSERT INTO strategy_lifecycle_state
                (strategy_name, artifact_sha256, previous_artifact_sha256, stage,
                 review_cycle, state_version, updated_by, updated_at_ms, policy_reason)
            VALUES
                (#{strategyName}, #{artifactSha256}, #{previousArtifactSha256}, #{stage},
                 #{reviewCycle}, #{stateVersion}, #{updatedBy}, #{updatedAtMs}, #{policyReason})
            """)
    int insertState(StrategyLifecycleStateRow row);

    @Select("SELECT * FROM strategy_lifecycle_state WHERE strategy_name=#{strategyName} FOR UPDATE")
    StrategyLifecycleStateRow selectStateForUpdate(@Param("strategyName") String strategyName);

    @Update("""
            UPDATE strategy_lifecycle_state
            SET artifact_sha256=#{row.artifactSha256},
                previous_artifact_sha256=#{row.previousArtifactSha256},
                stage=#{row.stage}, review_cycle=#{row.reviewCycle},
                state_version=#{row.stateVersion}, updated_by=#{row.updatedBy},
                updated_at_ms=#{row.updatedAtMs}, policy_reason=#{row.policyReason}
            WHERE strategy_name=#{row.strategyName} AND state_version=#{expectedVersion}
            """)
    int updateState(@Param("row") StrategyLifecycleStateRow row,
                    @Param("expectedVersion") long expectedVersion);

    @Insert("""
            INSERT INTO strategy_lifecycle_approval
                (approval_id, strategy_name, artifact_sha256, review_cycle, decision,
                 approval_scope_sha256, evidence_set_sha256, expires_at_ms,
                 approved_by, approved_at_ms)
            VALUES
                (#{approvalId}, #{strategyName}, #{artifactSha256}, #{reviewCycle}, #{decision},
                 #{approvalScopeSha256}, #{evidenceSetSha256}, #{expiresAtMs},
                 #{approvedBy}, #{approvedAtMs})
            """)
    int insertApproval(StrategyApprovalRow row);

    @Select("""
            SELECT * FROM strategy_lifecycle_approval
            WHERE strategy_name=#{strategyName} AND artifact_sha256=#{artifactSha256}
              AND review_cycle=#{reviewCycle}
            ORDER BY approved_at_ms, approval_id
            """)
    List<StrategyApprovalRow> selectApprovals(@Param("strategyName") String strategyName,
                                              @Param("artifactSha256") String artifactSha256,
                                              @Param("reviewCycle") long reviewCycle);

    @Insert("""
            INSERT INTO strategy_evidence_snapshot
                (snapshot_id, strategy_name, artifact_sha256, evidence_type, outcome,
                 evidence_sha256, evidence_uri, captured_at_ms, expires_at_ms,
                 recorded_by, recorded_at_ms)
            VALUES
                (#{snapshotId}, #{strategyName}, #{artifactSha256}, #{evidenceType}, #{outcome},
                 #{evidenceSha256}, #{evidenceUri}, #{capturedAtMs}, #{expiresAtMs},
                 #{recordedBy}, #{recordedAtMs})
            """)
    int insertEvidence(StrategyEvidenceRow row);

    @Select("""
            SELECT * FROM strategy_evidence_snapshot
            WHERE strategy_name=#{strategyName} AND artifact_sha256=#{artifactSha256}
            ORDER BY recorded_at_ms, snapshot_id
            """)
    List<StrategyEvidenceRow> selectEvidence(@Param("strategyName") String strategyName,
                                             @Param("artifactSha256") String artifactSha256);

    @Select("""
            SELECT * FROM strategy_lifecycle_transition
            WHERE strategy_name=#{strategyName}
            ORDER BY sequence_no DESC LIMIT 1
            """)
    StrategyLifecycleEventRow selectLatestEvent(@Param("strategyName") String strategyName);

    @Insert("""
            INSERT INTO strategy_lifecycle_transition
                (event_id, strategy_name, artifact_sha256, sequence_no, review_cycle,
                 from_stage, to_stage, action, accepted, reason_code, detail_message,
                 operator, evidence_set_sha256, occurred_at_ms,
                 previous_event_sha256, event_sha256)
            VALUES
                (#{eventId}, #{strategyName}, #{artifactSha256}, #{sequenceNo}, #{reviewCycle},
                 #{fromStage}, #{toStage}, #{action}, #{accepted}, #{reasonCode}, #{detailMessage},
                 #{operator}, #{evidenceSetSha256}, #{occurredAtMs},
                 #{previousEventSha256}, #{eventSha256})
            """)
    int insertEvent(StrategyLifecycleEventRow row);

    @Insert("""
            INSERT IGNORE INTO strategy_release_assignment_head
                (strategy_name, current_assignment_id, current_assignment_version)
            VALUES (#{strategyName}, NULL, -1)
            """)
    int insertReleaseAssignmentHeadIfMissing(@Param("strategyName") String strategyName);

    @Select("""
            SELECT strategy_name, current_assignment_id, current_assignment_version
            FROM strategy_release_assignment_head
            WHERE strategy_name=#{strategyName}
            FOR UPDATE
            """)
    StrategyReleaseAssignmentHeadRow selectReleaseAssignmentHeadForUpdate(
            @Param("strategyName") String strategyName);

    @Insert("""
            INSERT INTO strategy_release_assignment
                (assignment_id, strategy_name, artifact_sha256, minimum_stage,
                 governance_scope, governance_environment, account_scope, git_commit,
                 build_sha256, container_image_digest, configuration_sha256,
                 dataset_manifest_sha256, key_reference, run_id, policy_version,
                 governance_subject_sha256, expires_at_ms, assignment_version,
                 assigned_by, assigned_at_ms)
            VALUES
                (#{assignmentId}, #{strategyName}, #{artifactSha256}, #{minimumStage},
                 #{governanceScope}, #{governanceEnvironment}, #{accountScope}, #{gitCommit},
                 #{buildSha256}, #{containerImageDigest}, #{configurationSha256},
                 #{datasetManifestSha256}, #{keyReference}, #{runId}, #{policyVersion},
                 #{governanceSubjectSha256}, #{expiresAtMs}, #{assignmentVersion},
                 #{assignedBy}, #{assignedAtMs})
            """)
    int insertReleaseAssignment(StrategyReleaseAssignmentRow row);

    @Update("""
            UPDATE strategy_release_assignment_head
            SET current_assignment_id=#{assignmentId},
                current_assignment_version=#{assignmentVersion},
                update_time=CURRENT_TIMESTAMP(3)
            WHERE strategy_name=#{strategyName}
              AND current_assignment_version=#{expectedVersion}
            """)
    int advanceReleaseAssignmentHead(@Param("strategyName") String strategyName,
                                     @Param("assignmentId") String assignmentId,
                                     @Param("assignmentVersion") long assignmentVersion,
                                     @Param("expectedVersion") long expectedVersion);

    @Select("""
            SELECT a.*
            FROM strategy_release_assignment_head h
            JOIN strategy_release_assignment a
              ON a.assignment_id=h.current_assignment_id
            WHERE h.strategy_name=#{strategyName}
            """)
    StrategyReleaseAssignmentRow selectReleaseAssignment(@Param("strategyName") String strategyName);
}
