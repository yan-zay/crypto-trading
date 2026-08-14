package com.tj.crypto.trading.paper.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PaperFundingSettlementMapper {
    @Insert("""
            INSERT INTO paper_funding_settlement
                (funding_event_id, account_id, position_id, exchange, symbol,
                 funding_rate, mark_price, funding_amount, event_time_ms)
            VALUES (#{fundingEventId}, #{accountId}, #{positionId}, #{exchange}, #{symbol},
                    #{fundingRate}, #{markPrice}, #{fundingAmount}, #{eventTimeMs})
            """)
    int insert(PaperFundingSettlementDO settlement);

    @Select("SELECT COUNT(*) FROM paper_funding_settlement WHERE funding_event_id=#{eventId}")
    int exists(@Param("eventId") String eventId);

    @Select("SELECT * FROM paper_funding_settlement WHERE funding_event_id=#{eventId}")
    PaperFundingSettlementDO selectById(@Param("eventId") String eventId);

    @Select("""
            SELECT * FROM paper_funding_settlement WHERE account_id=#{accountId}
            ORDER BY event_time_ms DESC LIMIT #{limit}
            """)
    List<PaperFundingSettlementDO> selectByAccount(@Param("accountId") String accountId,
                                                   @Param("limit") int limit);
}
