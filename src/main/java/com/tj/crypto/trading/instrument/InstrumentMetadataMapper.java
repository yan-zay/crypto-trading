package com.tj.crypto.trading.instrument;

import com.tj.crypto.mapper.BaseMapperX;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface InstrumentMetadataMapper extends BaseMapperX<InstrumentMetadataDO> {
    @Select("""
            SELECT * FROM instrument_metadata
            WHERE exchange=#{exchange} AND market_type=#{marketType} AND symbol=#{symbol}
              AND valid_from_ms <= #{asOf}
              AND (valid_to_ms IS NULL OR valid_to_ms > #{asOf})
            ORDER BY valid_from_ms DESC LIMIT 1
            """)
    InstrumentMetadataDO selectActive(@Param("exchange") String exchange,
                                      @Param("marketType") String marketType,
                                      @Param("symbol") String symbol,
                                      @Param("asOf") long asOf);

    @Select("""
            SELECT * FROM instrument_metadata
            WHERE valid_to_ms IS NULL
            ORDER BY exchange, market_type, symbol
            """)
    List<InstrumentMetadataDO> selectCurrent();

    @Update("""
            UPDATE instrument_metadata SET valid_to_ms=#{validTo}, update_time=CURRENT_TIMESTAMP
            WHERE metadata_id=#{metadataId} AND valid_to_ms IS NULL
            """)
    int closeVersion(@Param("metadataId") long metadataId, @Param("validTo") long validTo);
}
