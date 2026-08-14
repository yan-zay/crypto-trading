package com.tj.crypto.trading.venue.fencing;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Atomic MySQL operations for the execution-writer lease. */
@Mapper
public interface ExecutionWriterLeaseMapper {

    @Insert("""
            INSERT IGNORE INTO execution_writer_lease
                (lease_scope, owner_id, fencing_token, lease_until_ms, heartbeat_at_ms)
            VALUES (#{scope}, '', 0, 0, 0)
            """)
    int ensureExists(@Param("scope") String scope);

    /**
     * Uses the database clock so hosts with different clocks cannot overlap ownership. The token
     * advances only when ownership changes; a normal heartbeat keeps the same token.
     */
    @Update("""
            UPDATE execution_writer_lease
            SET fencing_token = fencing_token + IF(owner_id = #{ownerId}, 0, 1),
                owner_id = #{ownerId},
                heartbeat_at_ms = CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED),
                lease_until_ms = CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED) + #{ttlMs}
            WHERE lease_scope = #{scope}
              AND (owner_id = #{ownerId}
                   OR lease_until_ms <= CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED))
            """)
    int tryAcquireOrRenew(@Param("scope") String scope,
                          @Param("ownerId") String ownerId,
                          @Param("ttlMs") long ttlMs);

    @Select("""
            SELECT lease_scope, owner_id, fencing_token, lease_until_ms, heartbeat_at_ms,
                   CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED) AS database_now_ms
            FROM execution_writer_lease
            WHERE lease_scope = #{scope}
            """)
    ExecutionWriterLeaseRow selectState(@Param("scope") String scope);
}
