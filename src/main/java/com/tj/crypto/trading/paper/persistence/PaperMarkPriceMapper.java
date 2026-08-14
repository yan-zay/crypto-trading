package com.tj.crypto.trading.paper.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PaperMarkPriceMapper {
    @Insert("""
            INSERT INTO paper_mark_price
                (exchange, market_type, symbol, price, high_price, low_price,
                 base_volume, event_time_ms, source)
            VALUES (#{exchange}, #{marketType}, #{symbol}, #{price}, #{highPrice}, #{lowPrice},
                    #{baseVolume}, #{eventTimeMs}, #{source})
            ON DUPLICATE KEY UPDATE
                price=IF(VALUES(event_time_ms) >= event_time_ms, VALUES(price), price),
                high_price=IF(VALUES(event_time_ms) >= event_time_ms, VALUES(high_price), high_price),
                low_price=IF(VALUES(event_time_ms) >= event_time_ms, VALUES(low_price), low_price),
                base_volume=IF(VALUES(event_time_ms) >= event_time_ms, VALUES(base_volume), base_volume),
                source=IF(VALUES(event_time_ms) >= event_time_ms, VALUES(source), source),
                event_time_ms=GREATEST(event_time_ms, VALUES(event_time_ms)),
                update_time=CURRENT_TIMESTAMP
            """)
    int upsert(PaperMarkPriceDO mark);

    @Select("""
            SELECT * FROM paper_mark_price
            WHERE exchange=#{exchange} AND market_type=#{marketType} AND symbol=#{symbol}
            """)
    PaperMarkPriceDO select(@Param("exchange") String exchange,
                            @Param("marketType") String marketType,
                            @Param("symbol") String symbol);

    @Select("SELECT * FROM paper_mark_price ORDER BY exchange, market_type, symbol")
    List<PaperMarkPriceDO> selectAll();
}
