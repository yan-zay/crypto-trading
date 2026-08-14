package com.tj.crypto.observability.slo;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SloSnapshotMapper {
    @Insert("""
            INSERT INTO slo_snapshot
                (snapshot_id, slo_name, window_start_ms, window_end_ms, target_value,
                 actual_value, compliant, error_budget_remaining_pct, sample_count, detail_json)
            VALUES (#{snapshotId}, #{sloName}, #{windowStartMs}, #{windowEndMs}, #{targetValue},
                    #{actualValue}, #{compliant}, #{errorBudgetRemainingPct}, #{sampleCount},
                    CAST(#{detailJson} AS JSON))
            ON DUPLICATE KEY UPDATE actual_value=VALUES(actual_value),
                compliant=VALUES(compliant),
                error_budget_remaining_pct=VALUES(error_budget_remaining_pct),
                sample_count=VALUES(sample_count), detail_json=VALUES(detail_json)
            """)
    int upsert(SloSnapshotDO snapshot);

    @Select("""
            SELECT * FROM slo_snapshot
            WHERE (#{sloName} IS NULL OR slo_name=#{sloName})
            ORDER BY window_end_ms DESC LIMIT #{limit}
            """)
    List<SloSnapshotDO> selectRecent(@Param("sloName") String sloName,
                                     @Param("limit") int limit);

    @Select("""
            SELECT * FROM slo_snapshot
            WHERE slo_name=#{sloName} AND sample_count>0 AND window_end_ms>=#{minimumEndMs}
            ORDER BY window_end_ms DESC LIMIT 1
            """)
    SloSnapshotDO selectLatestNonEmpty(@Param("sloName") String sloName,
                                       @Param("minimumEndMs") long minimumEndMs);
}
