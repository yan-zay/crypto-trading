package com.tj.crypto.storage.mapper;

import com.tj.crypto.mapper.BaseMapperX;
import com.tj.crypto.storage.entity.TradeRecordDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 交易记录 Mapper。
 */
@Mapper
public interface TradeRecordMapper extends BaseMapperX<TradeRecordDO> {

    /**
     * 按交易对查询最近的交易记录。
     */
    default List<TradeRecordDO> selectBySymbol(String symbol, int limit) {
        return selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TradeRecordDO>()
                .eq(TradeRecordDO::getSymbol, symbol)
                .orderByDesc(TradeRecordDO::getExitTime)
                .last("LIMIT " + limit));
    }
}
