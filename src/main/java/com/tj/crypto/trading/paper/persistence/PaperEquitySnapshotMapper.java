package com.tj.crypto.trading.paper.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PaperEquitySnapshotMapper {
    @Insert("""
            INSERT INTO paper_equity_snapshot
                (snapshot_id, account_id, event_time_ms, balance, available_balance,
                 locked_margin, unrealized_pnl, equity)
            VALUES (#{snapshotId}, #{accountId}, #{eventTimeMs}, #{balance}, #{availableBalance},
                    #{lockedMargin}, #{unrealizedPnl}, #{equity})
            ON DUPLICATE KEY UPDATE balance=VALUES(balance),
                available_balance=VALUES(available_balance), locked_margin=VALUES(locked_margin),
                unrealized_pnl=VALUES(unrealized_pnl), equity=VALUES(equity)
            """)
    int upsert(PaperEquitySnapshotDO snapshot);

    @Select("""
            SELECT * FROM paper_equity_snapshot WHERE account_id=#{accountId}
            ORDER BY event_time_ms ASC LIMIT #{limit}
            """)
    List<PaperEquitySnapshotDO> selectByAccount(@Param("accountId") String accountId,
                                                @Param("limit") int limit);

    @Select("""
            SELECT * FROM paper_equity_snapshot WHERE account_id=#{accountId}
            ORDER BY event_time_ms DESC LIMIT 1
            """)
    PaperEquitySnapshotDO selectLatest(@Param("accountId") String accountId);
}
