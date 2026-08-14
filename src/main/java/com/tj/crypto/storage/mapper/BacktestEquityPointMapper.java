package com.tj.crypto.storage.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tj.crypto.mapper.BaseMapperX;
import com.tj.crypto.storage.entity.BacktestEquityPointDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface BacktestEquityPointMapper extends BaseMapperX<BacktestEquityPointDO> {
    default List<BacktestEquityPointDO> selectByRunId(String runId) {
        return selectList(new LambdaQueryWrapper<BacktestEquityPointDO>()
                .eq(BacktestEquityPointDO::getRunId, runId)
                .orderByAsc(BacktestEquityPointDO::getSequenceNo));
    }
}
