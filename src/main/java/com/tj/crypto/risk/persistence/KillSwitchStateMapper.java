package com.tj.crypto.risk.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface KillSwitchStateMapper {
    String GLOBAL_KEY = "GLOBAL";

    @Select("""
            SELECT state_key, mode, reason, changed_by, changed_at_ms, state_version
            FROM kill_switch_state WHERE state_key='GLOBAL'
            """)
    KillSwitchStateDO selectGlobal();

    @Select("""
            SELECT state_key, mode, reason, changed_by, changed_at_ms, state_version
            FROM kill_switch_state WHERE state_key='GLOBAL' FOR UPDATE
            """)
    KillSwitchStateDO selectGlobalForUpdate();

    @Insert("""
            INSERT INTO kill_switch_state
                (state_key, mode, reason, changed_by, changed_at_ms, state_version)
            VALUES
                ('GLOBAL', #{mode}, #{reason}, #{changedBy}, #{changedAtMs}, 0)
            """)
    int insertGlobal(@Param("mode") String mode,
                     @Param("reason") String reason,
                     @Param("changedBy") String changedBy,
                     @Param("changedAtMs") long changedAtMs);

    @Update("""
            UPDATE kill_switch_state
            SET mode=#{mode}, reason=#{reason}, changed_by=#{changedBy},
                changed_at_ms=#{changedAtMs}, state_version=state_version+1,
                update_time=CURRENT_TIMESTAMP
            WHERE state_key='GLOBAL' AND state_version=#{expectedVersion}
            """)
    int updateGlobalAtVersion(@Param("mode") String mode,
                              @Param("reason") String reason,
                              @Param("changedBy") String changedBy,
                              @Param("changedAtMs") long changedAtMs,
                              @Param("expectedVersion") long expectedVersion);
}
