package com.tj.crypto.trading.paper.persistence;

import com.tj.crypto.mapper.BaseMapperX;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface PaperAccountMapper extends BaseMapperX<PaperAccountDO> {
    @Select("SELECT * FROM paper_account WHERE status='RUNNING' ORDER BY started_at_ms DESC LIMIT 1")
    PaperAccountDO selectRunning();

    @Select("SELECT * FROM paper_account WHERE account_id=#{accountId} FOR UPDATE")
    PaperAccountDO selectForUpdate(@Param("accountId") String accountId);

    @Select("SELECT * FROM paper_account ORDER BY started_at_ms DESC LIMIT #{limit}")
    List<PaperAccountDO> selectRecent(@Param("limit") int limit);

    @Update("""
            UPDATE paper_account SET status='STOPPED', stopped_at_ms=#{stoppedAt},
                version=version+1, update_time=CURRENT_TIMESTAMP
            WHERE account_id=#{accountId} AND status='RUNNING'
            """)
    int stop(@Param("accountId") String accountId, @Param("stoppedAt") long stoppedAt);

    @Update("""
            UPDATE paper_account SET status='RUNNING', stopped_at_ms=NULL,
                version=version+1, update_time=CURRENT_TIMESTAMP
            WHERE account_id=#{accountId} AND status='STOPPED'
            """)
    int resume(@Param("accountId") String accountId);
}
