package com.tj.crypto.governance.evidence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ControlEvidenceMapper {
    @Insert("""
            INSERT INTO governance_control_evidence_head
                (control_kind, scope, environment, subject_sha256, policy_version,
                 latest_evidence_id, head_version)
            VALUES (#{kind}, #{scope}, #{environment}, #{subjectSha256}, #{policyVersion}, NULL, 0)
            """)
    int insertHead(@Param("kind") String kind,
                   @Param("scope") String scope,
                   @Param("environment") String environment,
                   @Param("subjectSha256") String subjectSha256,
                   @Param("policyVersion") String policyVersion);

    @Select("""
            SELECT latest_evidence_id, head_version
            FROM governance_control_evidence_head
            WHERE control_kind=#{kind} AND scope=#{scope} AND environment=#{environment}
              AND subject_sha256=#{subjectSha256} AND policy_version=#{policyVersion}
            FOR UPDATE
            """)
    ControlEvidenceHeadDO selectHeadForUpdate(@Param("kind") String kind,
                                              @Param("scope") String scope,
                                              @Param("environment") String environment,
                                              @Param("subjectSha256") String subjectSha256,
                                              @Param("policyVersion") String policyVersion);

    @Insert("""
            INSERT INTO governance_control_evidence
                (evidence_id, control_kind, scope, environment, subject_sha256, policy_version,
                 evidence_status, owner, issuer, started_at_ms, completed_at_ms, expires_at_ms,
                 registered_at_ms, artifact_uri, artifact_sha256, predecessor_evidence_id, notes)
            VALUES
                (#{evidenceId}, #{kind}, #{scope}, #{environment}, #{subjectSha256},
                 #{policyVersion}, #{status}, #{owner}, #{issuer}, #{startedAtMs},
                 #{completedAtMs}, #{expiresAtMs}, #{registeredAtMs}, #{artifactUri},
                 #{artifactSha256}, NULLIF(#{predecessorEvidenceId}, ''), #{notes})
            """)
    int insert(@Param("evidenceId") String evidenceId,
               @Param("kind") String kind,
               @Param("scope") String scope,
               @Param("environment") String environment,
               @Param("subjectSha256") String subjectSha256,
               @Param("policyVersion") String policyVersion,
               @Param("status") String status,
               @Param("owner") String owner,
               @Param("issuer") String issuer,
               @Param("startedAtMs") long startedAtMs,
               @Param("completedAtMs") long completedAtMs,
               @Param("expiresAtMs") long expiresAtMs,
               @Param("registeredAtMs") long registeredAtMs,
               @Param("artifactUri") String artifactUri,
               @Param("artifactSha256") String artifactSha256,
               @Param("predecessorEvidenceId") String predecessorEvidenceId,
               @Param("notes") String notes);

    @Update("""
            UPDATE governance_control_evidence_head
            SET latest_evidence_id=#{newEvidenceId}, head_version=head_version+1,
                update_time=CURRENT_TIMESTAMP(6)
            WHERE control_kind=#{kind} AND scope=#{scope} AND environment=#{environment}
              AND subject_sha256=#{subjectSha256} AND policy_version=#{policyVersion}
              AND head_version=#{expectedHeadVersion}
              AND latest_evidence_id <=> NULLIF(#{expectedEvidenceId}, '')
            """)
    int advanceHead(@Param("kind") String kind,
                    @Param("scope") String scope,
                    @Param("environment") String environment,
                    @Param("subjectSha256") String subjectSha256,
                    @Param("policyVersion") String policyVersion,
                    @Param("expectedHeadVersion") long expectedHeadVersion,
                    @Param("expectedEvidenceId") String expectedEvidenceId,
                    @Param("newEvidenceId") String newEvidenceId);

    @Select("""
            SELECT evidence_id, control_kind, scope, environment, subject_sha256,
                   policy_version, evidence_status, owner, issuer, started_at_ms,
                   completed_at_ms, expires_at_ms, registered_at_ms, artifact_uri,
                   artifact_sha256, predecessor_evidence_id, notes
            FROM governance_control_evidence
            WHERE evidence_id=#{evidenceId}
            """)
    ControlEvidenceDO selectById(@Param("evidenceId") String evidenceId);

    @Select("""
            SELECT e.evidence_id, e.control_kind, e.scope, e.environment,
                   e.subject_sha256, e.policy_version, e.evidence_status,
                   e.owner, e.issuer, e.started_at_ms, e.completed_at_ms, e.expires_at_ms,
                   e.registered_at_ms, e.artifact_uri, e.artifact_sha256,
                   e.predecessor_evidence_id, e.notes
            FROM governance_control_evidence e
            JOIN governance_control_evidence_head h
              ON h.control_kind=e.control_kind AND h.scope=e.scope
             AND h.environment=e.environment AND h.subject_sha256=e.subject_sha256
             AND h.policy_version=e.policy_version AND h.latest_evidence_id=e.evidence_id
            WHERE h.control_kind=#{kind} AND h.scope=#{scope} AND h.environment=#{environment}
              AND h.subject_sha256=#{subjectSha256} AND h.policy_version=#{policyVersion}
            """)
    ControlEvidenceDO selectLatest(@Param("kind") String kind,
                                   @Param("scope") String scope,
                                   @Param("environment") String environment,
                                   @Param("subjectSha256") String subjectSha256,
                                   @Param("policyVersion") String policyVersion);
}
