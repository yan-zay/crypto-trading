package com.tj.crypto.backtest.job;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface BacktestJobMapper {
    @Insert("""
            INSERT INTO backtest_job
                (job_id, job_type, status, request_json, progress_pct, stage, random_seed,
                 created_by, created_at_ms, version)
            VALUES (#{jobId}, #{jobType}, #{status}, CAST(#{requestJson} AS JSON),
                    #{progressPct}, #{stage}, #{randomSeed}, #{createdBy}, #{createdAtMs}, 0)
            """)
    int insert(BacktestJobDO job);

    @Select("SELECT * FROM backtest_job WHERE job_id=#{jobId}")
    BacktestJobDO select(@Param("jobId") String jobId);

    @Select("SELECT job_id FROM backtest_job WHERE status='QUEUED' ORDER BY created_at_ms LIMIT #{limit}")
    List<String> selectQueued(@Param("limit") int limit);

    @Update("""
            UPDATE backtest_job SET status='RUNNING', stage='STARTING', progress_pct=1,
                result_id=#{resultId}, worker_id=#{workerId}, started_at_ms=#{now},
                heartbeat_at_ms=#{now}, version=version+1
            WHERE job_id=#{jobId} AND status='QUEUED'
            """)
    int claim(@Param("jobId") String jobId, @Param("resultId") String resultId,
              @Param("workerId") String workerId, @Param("now") long now);

    @Update("""
            UPDATE backtest_job SET progress_pct=#{progress}, stage=#{stage},
                heartbeat_at_ms=#{now}, version=version+1
            WHERE job_id=#{jobId} AND status='RUNNING'
            """)
    int progress(@Param("jobId") String jobId, @Param("progress") int progress,
                 @Param("stage") String stage, @Param("now") long now);

    @Update("""
            UPDATE backtest_job SET status='COMPLETED', progress_pct=100, stage='COMPLETED',
                completed_at_ms=#{now}, heartbeat_at_ms=#{now}, version=version+1
            WHERE job_id=#{jobId} AND status IN ('RUNNING','CANCEL_REQUESTED')
            """)
    int complete(@Param("jobId") String jobId, @Param("now") long now);

    @Update("""
            UPDATE backtest_job SET status=#{status}, stage=#{status}, error_code=#{errorCode},
                error_message=#{errorMessage}, completed_at_ms=#{now}, heartbeat_at_ms=#{now},
                version=version+1
            WHERE job_id=#{jobId} AND status IN ('RUNNING','CANCEL_REQUESTED')
            """)
    int finishFailure(@Param("jobId") String jobId, @Param("status") String status,
                      @Param("errorCode") String errorCode,
                      @Param("errorMessage") String errorMessage, @Param("now") long now);

    @Update("""
            UPDATE backtest_job SET
                stage=CASE WHEN status='QUEUED' THEN 'CANCELLED' ELSE 'CANCEL_REQUESTED' END,
                completed_at_ms=CASE WHEN status='QUEUED' THEN #{now} ELSE completed_at_ms END,
                status=CASE WHEN status='QUEUED' THEN 'CANCELLED' ELSE 'CANCEL_REQUESTED' END,
                version=version+1
            WHERE job_id=#{jobId} AND status IN ('QUEUED','RUNNING')
            """)
    int requestCancel(@Param("jobId") String jobId, @Param("now") long now);

    @Select("SELECT COUNT(*) FROM backtest_job WHERE job_id=#{jobId} AND status='CANCEL_REQUESTED'")
    int cancellationRequested(@Param("jobId") String jobId);

    @Update("""
            UPDATE backtest_job SET status='QUEUED', stage='RECOVERED', progress_pct=0,
                worker_id=NULL, started_at_ms=NULL, heartbeat_at_ms=NULL, result_id=NULL,
                version=version+1 WHERE status='RUNNING'
            """)
    int recoverRunning();

    @Update("""
            UPDATE backtest_job SET status='CANCELLED', stage='CANCELLED', completed_at_ms=#{now},
                version=version+1 WHERE status='CANCEL_REQUESTED'
            """)
    int recoverCancellation(@Param("now") long now);

    @Update("""
            UPDATE backtest_job SET status='QUEUED', stage='QUEUED', progress_pct=0,
                worker_id=NULL, started_at_ms=NULL, heartbeat_at_ms=NULL, result_id=NULL,
                version=version+1 WHERE job_id=#{jobId} AND status='RUNNING'
            """)
    int releaseClaim(@Param("jobId") String jobId);

    @Select("""
            SELECT * FROM backtest_job
            WHERE (#{status} IS NULL OR status=#{status})
            ORDER BY created_at_ms DESC LIMIT #{limit}
            """)
    List<BacktestJobDO> selectRecent(@Param("status") String status, @Param("limit") int limit);
}
