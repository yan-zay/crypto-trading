package com.tj.crypto.trading.reconciliation;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ReconciliationMapper {
    @Insert("""
            INSERT INTO reconciliation_incident
                (incident_id, account_id, incident_type, severity, aggregate_type,
                 aggregate_id, expected_json, actual_json, status, detected_at_ms, fingerprint)
            VALUES (#{incidentId}, #{accountId}, #{incidentType}, #{severity}, #{aggregateType},
                    #{aggregateId}, CAST(#{expectedJson} AS JSON), CAST(#{actualJson} AS JSON),
                    'OPEN', #{detectedAtMs}, #{fingerprint})
            ON DUPLICATE KEY UPDATE detected_at_ms=VALUES(detected_at_ms),
                expected_json=VALUES(expected_json), actual_json=VALUES(actual_json),
                severity=VALUES(severity), update_time=CURRENT_TIMESTAMP
            """)
    int upsertOpen(ReconciliationIncidentDO incident);

    @Select("""
            SELECT * FROM reconciliation_incident
            WHERE (#{accountId} IS NULL OR account_id=#{accountId})
              AND (#{status} IS NULL OR status=#{status})
            ORDER BY detected_at_ms DESC LIMIT #{limit}
            """)
    List<ReconciliationIncidentDO> selectRecent(@Param("accountId") String accountId,
                                                 @Param("status") String status,
                                                 @Param("limit") int limit);

    @Select("SELECT * FROM reconciliation_incident WHERE incident_id=#{incidentId} FOR UPDATE")
    ReconciliationIncidentDO selectForUpdate(@Param("incidentId") String incidentId);

    @Update("""
            UPDATE reconciliation_incident SET status='RESOLVED', resolved_at_ms=#{resolvedAt},
                resolution=#{resolution}, resolved_by=#{resolvedBy}, update_time=CURRENT_TIMESTAMP
            WHERE incident_id=#{incidentId} AND status='OPEN'
            """)
    int resolve(@Param("incidentId") String incidentId, @Param("resolvedAt") long resolvedAt,
                @Param("resolution") String resolution, @Param("resolvedBy") String resolvedBy);

    @Select("SELECT COUNT(*) FROM reconciliation_incident WHERE status='OPEN'")
    long countOpen();
}
