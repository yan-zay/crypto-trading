package com.tj.crypto.storage.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tj.crypto.mapper.BaseMapperX;
import com.tj.crypto.storage.entity.BacktestTradeDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface BacktestTradeMapper extends BaseMapperX<BacktestTradeDO> {
    default List<BacktestTradeDO> selectByRunId(String runId) {
        return selectList(new LambdaQueryWrapper<BacktestTradeDO>()
                .eq(BacktestTradeDO::getRunId, runId)
                .orderByAsc(BacktestTradeDO::getSequenceNo));
    }
}
