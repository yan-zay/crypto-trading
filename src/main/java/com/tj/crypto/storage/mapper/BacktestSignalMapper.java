package com.tj.crypto.storage.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tj.crypto.mapper.BaseMapperX;
import com.tj.crypto.storage.entity.BacktestSignalDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface BacktestSignalMapper extends BaseMapperX<BacktestSignalDO> {
    default List<BacktestSignalDO> selectByRunId(String runId) {
        return selectList(new LambdaQueryWrapper<BacktestSignalDO>()
                .eq(BacktestSignalDO::getRunId, runId)
                .orderByAsc(BacktestSignalDO::getSequenceNo));
    }
}
