package com.tj.crypto.reliability.outbox;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProcessedEventMapper {
    @Select("""
            SELECT COUNT(*) FROM processed_event
            WHERE consumer_name=#{consumer} AND event_id=#{eventId}
            """)
    int exists(@Param("consumer") String consumer, @Param("eventId") String eventId);

    @Insert("""
            INSERT IGNORE INTO processed_event (consumer_name, event_id, processed_at_ms)
            VALUES (#{consumer}, #{eventId}, #{processedAt})
            """)
    int markProcessed(@Param("consumer") String consumer, @Param("eventId") String eventId,
                      @Param("processedAt") long processedAt);
}
