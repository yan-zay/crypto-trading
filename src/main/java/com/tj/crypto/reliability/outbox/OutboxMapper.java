package com.tj.crypto.reliability.outbox;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface OutboxMapper {
    @Insert("""
            INSERT INTO event_outbox
                (event_id, aggregate_type, aggregate_id, event_type, payload_json,
                 correlation_id, status, attempts, available_at_ms)
            VALUES (#{eventId}, #{aggregateType}, #{aggregateId}, #{eventType},
                    CAST(#{payloadJson} AS JSON), #{correlationId}, #{status}, #{attempts}, #{availableAtMs})
            """)
    int insert(OutboxEventDO event);

    @Select("""
            SELECT candidate.* FROM event_outbox candidate
            WHERE (
                    (candidate.status IN ('PENDING','RETRY') AND candidate.available_at_ms <= #{now})
                    OR (candidate.status='PROCESSING' AND candidate.claim_until_ms < #{now})
                  )
              AND NOT EXISTS (
                    SELECT 1 FROM event_outbox earlier
                    WHERE earlier.aggregate_type=candidate.aggregate_type
                      AND earlier.aggregate_id=candidate.aggregate_id
                      AND earlier.event_sequence<candidate.event_sequence
                      AND earlier.status<>'PUBLISHED'
                  )
            ORDER BY candidate.event_sequence LIMIT #{limit}
            """)
    List<OutboxEventDO> selectClaimable(@Param("now") long now, @Param("limit") int limit);

    @Update("""
            UPDATE event_outbox SET status='PROCESSING', claimed_by=#{worker},
                claim_until_ms=#{claimUntil}, attempts=attempts+1
            WHERE event_id=#{eventId}
              AND ((status IN ('PENDING','RETRY') AND available_at_ms <= #{now})
                    OR (status='PROCESSING' AND claim_until_ms < #{now}))
            """)
    int claim(@Param("eventId") String eventId, @Param("worker") String worker,
              @Param("claimUntil") long claimUntil, @Param("now") long now);

    @Update("""
            UPDATE event_outbox SET status='PUBLISHED', published_at_ms=#{now},
                claimed_by=NULL, claim_until_ms=NULL, last_error=NULL
            WHERE event_id=#{eventId} AND claimed_by=#{worker}
            """)
    int markPublished(@Param("eventId") String eventId, @Param("worker") String worker,
                      @Param("now") long now);

    @Update("""
            UPDATE event_outbox SET status=#{status}, available_at_ms=#{availableAt},
                claimed_by=NULL, claim_until_ms=NULL, last_error=#{error}
            WHERE event_id=#{eventId} AND claimed_by=#{worker}
            """)
    int markFailed(@Param("eventId") String eventId, @Param("worker") String worker,
                   @Param("status") String status, @Param("availableAt") long availableAt,
                   @Param("error") String error);

    @Select("SELECT COUNT(*) FROM event_outbox WHERE status IN ('PENDING','RETRY','PROCESSING')")
    long countBacklog();

    @Select("SELECT MIN(available_at_ms) FROM event_outbox WHERE status IN ('PENDING','RETRY','PROCESSING')")
    Long oldestPendingAt();

    @Select("""
            SELECT * FROM event_outbox
            WHERE (#{status} IS NULL OR status=#{status})
            ORDER BY event_sequence DESC LIMIT #{limit}
            """)
    List<OutboxEventDO> selectRecent(@Param("status") String status, @Param("limit") int limit);

    @Update("""
            UPDATE event_outbox SET status='RETRY', available_at_ms=#{now}, attempts=0,
                claimed_by=NULL, claim_until_ms=NULL, last_error=NULL
            WHERE event_id=#{eventId} AND status='DEAD_LETTER'
            """)
    int retryDeadLetter(@Param("eventId") String eventId, @Param("now") long now);
}
