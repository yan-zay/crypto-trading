package com.tj.crypto.trading.paper.persistence;

import com.tj.crypto.mapper.BaseMapperX;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface PaperPositionMapper extends BaseMapperX<PaperPositionDO> {
    @Select("SELECT * FROM paper_position WHERE account_id=#{accountId} ORDER BY updated_at_ms DESC")
    List<PaperPositionDO> selectByAccount(@Param("accountId") String accountId);

    @Select("""
            SELECT * FROM paper_position
            WHERE account_id=#{accountId} AND exchange=#{exchange}
              AND market_type=#{marketType} AND symbol=#{symbol}
            FOR UPDATE
            """)
    PaperPositionDO selectForUpdate(@Param("accountId") String accountId,
                                    @Param("exchange") String exchange,
                                    @Param("marketType") String marketType,
                                    @Param("symbol") String symbol);

    @Update("""
            UPDATE paper_position SET side=#{side}, quantity=#{quantity}, entry_price=#{entryPrice},
                mark_price=#{markPrice}, contract_multiplier=#{contractMultiplier}, leverage=#{leverage},
                margin_mode=#{marginMode}, initial_margin=#{initialMargin},
                maintenance_margin=#{maintenanceMargin}, open_fee=#{openFee}, funding=#{funding},
                realized_pnl=#{realizedPnl}, unrealized_pnl=#{unrealizedPnl},
                strategy_id=#{strategyId}, open_order_id=#{openOrderId},
                opened_at_ms=#{openedAtMs}, updated_at_ms=#{updatedAtMs},
                version=version+1, update_time=CURRENT_TIMESTAMP
            WHERE position_id=#{positionId}
            """)
    int updatePosition(PaperPositionDO position);

    @Delete("DELETE FROM paper_position WHERE position_id=#{positionId}")
    int deletePosition(@Param("positionId") String positionId);
}
