package com.tj.crypto.storage.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tj.crypto.mapper.BaseMapperX;
import com.tj.crypto.storage.entity.BarEventDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * K 线数据 Mapper。
 */
@Mapper
public interface BarEventMapper extends BaseMapperX<BarEventDO> {

    @Insert("""
            INSERT INTO bar_event (
                exchange, market_type, symbol, timeframe, open_time,
                open_price, high_price, low_price, close_price, volume, quote_volume
            ) VALUES (
                #{b.exchange}, #{b.marketType}, #{b.symbol}, #{b.timeframe}, #{b.openTime},
                #{b.openPrice}, #{b.highPrice}, #{b.lowPrice}, #{b.closePrice}, #{b.volume}, #{b.quoteVolume}
            ) ON DUPLICATE KEY UPDATE
                open_price=VALUES(open_price), high_price=VALUES(high_price),
                low_price=VALUES(low_price), close_price=VALUES(close_price),
                volume=VALUES(volume), quote_volume=VALUES(quote_volume),
                update_time=CURRENT_TIMESTAMP
            """)
    int upsert(@Param("b") BarEventDO bar);

    default void upsertBatch(Collection<BarEventDO> bars) {
        bars.forEach(this::upsert);
    }

    /**
     * 按交易对和时间周期查询最近的 K 线数据。
     */
    default List<BarEventDO> selectRecent(String symbol, String timeframe, int limit) {
        return selectList(new LambdaQueryWrapper<BarEventDO>()
                .eq(BarEventDO::getSymbol, symbol)
                .eq(BarEventDO::getTimeframe, timeframe)
                .orderByDesc(BarEventDO::getOpenTime)
                .last("LIMIT " + limit));
    }

    /**
     * 按交易对、时间周期和时间范围查询 K 线数据（正序）。
     */
    default List<BarEventDO> selectByTimeRange(String symbol, String timeframe,
                                                 long fromTime, long toTime) {
        return selectList(new LambdaQueryWrapper<BarEventDO>()
                .eq(BarEventDO::getSymbol, symbol)
                .eq(BarEventDO::getTimeframe, timeframe)
                .ge(BarEventDO::getOpenTime, fromTime)
                .le(BarEventDO::getOpenTime, toTime)
                .orderByAsc(BarEventDO::getOpenTime));
    }

    /** 按完整市场序列标识查询，避免跨交易所或跨市场数据污染。 */
    default List<BarEventDO> selectByTimeRange(String exchange, String marketType,
                                                String symbol, String timeframe,
                                                long fromTime, long toTime) {
        return selectList(new LambdaQueryWrapper<BarEventDO>()
                .eq(BarEventDO::getExchange, exchange)
                .eq(BarEventDO::getMarketType, marketType)
                .eq(BarEventDO::getSymbol, symbol)
                .eq(BarEventDO::getTimeframe, timeframe)
                .ge(BarEventDO::getOpenTime, fromTime)
                .le(BarEventDO::getOpenTime, toTime)
                .orderByAsc(BarEventDO::getOpenTime));
    }
}
