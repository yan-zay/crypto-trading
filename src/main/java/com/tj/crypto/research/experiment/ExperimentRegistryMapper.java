package com.tj.crypto.research.experiment;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** Insert-only persistence port plus read models for the immutable registry. */
@Mapper
public interface ExperimentRegistryMapper {
    @Insert("""
            INSERT INTO research_experiment_registration
                (registration_id, idempotency_key, registration_sha256, experiment_name,
                 hypothesis, protocol_version, model_version, prompt_version,
                 declared_trial_count, registered_by, registered_at_ms)
            VALUES (#{registrationId}, #{idempotencyKey}, #{registrationSha256},
                    #{experimentName}, #{hypothesis}, #{protocolVersion}, #{modelVersion},
                    #{promptVersion}, #{declaredTrialCount}, #{registeredBy}, #{registeredAtMs})
            """)
    int insertRegistration(ExperimentRegistrationDO registration);

    @Insert("""
            INSERT INTO research_experiment_chain_head
                (registration_id, last_event_sequence, last_event_sha256, version)
            VALUES (#{registrationId}, #{lastEventSequence}, #{lastEventSha256}, #{version})
            """)
    int insertChainHead(ExperimentChainHeadDO chainHead);

    @Insert("""
            INSERT INTO research_experiment_artifact
                (registration_id, artifact_id, artifact_kind, artifact_version,
                 artifact_uri, artifact_sha256, media_type)
            VALUES (#{registrationId}, #{artifactId}, #{artifactKind}, #{artifactVersion},
                    #{artifactUri}, #{artifactSha256}, #{mediaType})
            """)
    int insertArtifact(ExperimentArtifactDO artifact);

    @Insert("""
            INSERT INTO research_experiment_trial
                (registration_id, trial_id, trial_ordinal, hypothesis, random_seed,
                 parameter_sha256)
            VALUES (#{registrationId}, #{trialId}, #{trialOrdinal}, #{hypothesis},
                    #{randomSeed}, #{parameterSha256})
            """)
    int insertTrial(ExperimentTrialDO trial);

    @Insert("""
            INSERT INTO research_experiment_event
                (event_id, registration_id, trial_id, command_idempotency_key,
                 command_sha256, event_sequence, event_type,
                 from_status, to_status, reason_code, detail_message,
                 observed_provenance_sha256, result_artifact_uri,
                 result_artifact_version, result_artifact_sha256, recorded_by,
                 occurred_at_ms, previous_event_sha256, event_sha256)
            VALUES (#{eventId}, #{registrationId}, #{trialId},
                    #{commandIdempotencyKey}, #{commandSha256}, #{eventSequence},
                    #{eventType}, #{fromStatus}, #{toStatus}, #{reasonCode},
                    #{detailMessage}, #{observedProvenanceSha256}, #{resultArtifactUri},
                    #{resultArtifactVersion}, #{resultArtifactSha256}, #{recordedBy},
                    #{occurredAtMs}, #{previousEventSha256}, #{eventSha256})
            """)
    int insertEvent(ExperimentEventDO event);

    @Update("""
            UPDATE research_experiment_chain_head
            SET last_event_sequence=#{nextSequence}, last_event_sha256=#{nextSha256},
                version=version+1
            WHERE registration_id=#{registrationId}
              AND last_event_sequence=#{expectedSequence}
              AND last_event_sha256=#{expectedSha256}
              AND version=#{expectedVersion}
            """)
    int compareAndSetChainHead(@Param("registrationId") String registrationId,
                               @Param("expectedSequence") long expectedSequence,
                               @Param("expectedSha256") String expectedSha256,
                               @Param("expectedVersion") long expectedVersion,
                               @Param("nextSequence") long nextSequence,
                               @Param("nextSha256") String nextSha256);

    @Select("SELECT * FROM research_experiment_registration WHERE registration_id=#{registrationId}")
    ExperimentRegistrationDO selectRegistration(@Param("registrationId") String registrationId);

    @Select("""
            SELECT * FROM research_experiment_registration
            WHERE registration_id=#{registrationId} FOR UPDATE
            """)
    ExperimentRegistrationDO selectRegistrationForUpdate(@Param("registrationId") String registrationId);

    @Select("""
            SELECT * FROM research_experiment_registration
            WHERE idempotency_key=#{idempotencyKey} FOR UPDATE
            """)
    ExperimentRegistrationDO selectRegistrationByIdempotencyKeyForUpdate(
            @Param("idempotencyKey") String idempotencyKey);

    @Select("""
            SELECT * FROM research_experiment_registration
            WHERE registration_sha256=#{sha256} FOR UPDATE
            """)
    ExperimentRegistrationDO selectRegistrationBySha256ForUpdate(@Param("sha256") String sha256);

    @Select("""
            SELECT * FROM research_experiment_chain_head
            WHERE registration_id=#{registrationId}
            """)
    ExperimentChainHeadDO selectChainHead(@Param("registrationId") String registrationId);

    @Select("""
            SELECT * FROM research_experiment_chain_head
            WHERE registration_id=#{registrationId} FOR UPDATE
            """)
    ExperimentChainHeadDO selectChainHeadForUpdate(@Param("registrationId") String registrationId);

    @Select("""
            SELECT * FROM research_experiment_registration
            ORDER BY registered_at_ms DESC, registration_id DESC LIMIT #{limit}
            """)
    List<ExperimentRegistrationDO> selectRecentRegistrations(@Param("limit") int limit);

    @Select("""
            SELECT * FROM research_experiment_artifact
            WHERE registration_id=#{registrationId} ORDER BY artifact_id
            """)
    List<ExperimentArtifactDO> selectArtifacts(@Param("registrationId") String registrationId);

    @Select("""
            SELECT * FROM research_experiment_trial
            WHERE registration_id=#{registrationId} ORDER BY trial_ordinal
            """)
    List<ExperimentTrialDO> selectTrials(@Param("registrationId") String registrationId);

    @Select("""
            SELECT * FROM research_experiment_event
            WHERE registration_id=#{registrationId} ORDER BY event_sequence
            """)
    List<ExperimentEventDO> selectEvents(@Param("registrationId") String registrationId);

    @Select("""
            SELECT * FROM research_experiment_event
            WHERE registration_id=#{registrationId} AND trial_id=#{trialId}
            ORDER BY event_sequence DESC LIMIT 1
            """)
    ExperimentEventDO selectLatestTrialEvent(@Param("registrationId") String registrationId,
                                             @Param("trialId") String trialId);

    @Select("""
            SELECT * FROM research_experiment_event
            WHERE registration_id=#{registrationId}
              AND command_idempotency_key=#{commandIdempotencyKey}
            """)
    ExperimentEventDO selectEventByCommandKey(
            @Param("registrationId") String registrationId,
            @Param("commandIdempotencyKey") String commandIdempotencyKey);

    @Select("""
            SELECT * FROM research_experiment_event
            WHERE registration_id=#{registrationId}
            ORDER BY event_sequence DESC LIMIT 1
            """)
    ExperimentEventDO selectLastEvent(@Param("registrationId") String registrationId);
}
