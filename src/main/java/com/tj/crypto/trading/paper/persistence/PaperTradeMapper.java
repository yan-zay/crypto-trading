package com.tj.crypto.trading.paper.persistence;

import com.tj.crypto.mapper.BaseMapperX;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

import java.util.List;

@Mapper
public interface PaperTradeMapper extends BaseMapperX<PaperTradeDO> {
    @Select("""
            SELECT * FROM paper_trade WHERE account_id=#{accountId}
            ORDER BY closed_at_ms DESC LIMIT #{limit}
            """)
    List<PaperTradeDO> selectByAccount(@Param("accountId") String accountId,
                                       @Param("limit") int limit);

    @Select("""
            SELECT COALESCE(ABS(SUM(CASE WHEN net_pnl < 0 THEN net_pnl ELSE 0 END)),0)
            FROM paper_trade
            WHERE account_id=#{accountId} AND closed_at_ms>=#{fromMs} AND closed_at_ms<=#{toMs}
            """)
    BigDecimal sumLoss(@Param("accountId") String accountId,
                       @Param("fromMs") long fromMs,
                       @Param("toMs") long toMs);
}
