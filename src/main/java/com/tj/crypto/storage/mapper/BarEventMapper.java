package com.tj.crypto.storage.mapper;

import com.tj.crypto.mapper.BaseMapperX;
import com.tj.crypto.storage.entity.BarEventDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * K 线数据 Mapper。
 */
@Mapper
public interface BarEventMapper extends BaseMapperX<BarEventDO> {

    /**
     * 按交易对和时间周期查询最近的 K 线数据。
     */
    default List<BarEventDO> selectRecent(String symbol, String timeframe, int limit) {
        return selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BarEventDO>()
                .eq(BarEventDO::getSymbol, symbol)
                .eq(BarEventDO::getTimeframe, timeframe)
                .orderByDesc(BarEventDO::getOpenTime)
                .last("LIMIT " + limit));
    }
}
