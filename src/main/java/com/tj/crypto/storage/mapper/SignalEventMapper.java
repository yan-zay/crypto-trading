package com.tj.crypto.storage.mapper;

import com.tj.crypto.mapper.BaseMapperX;
import com.tj.crypto.storage.entity.SignalEventDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 策略信号 Mapper。
 */
@Mapper
public interface SignalEventMapper extends BaseMapperX<SignalEventDO> {

    /**
     * 按策略名称查询最近的信号。
     */
    default List<SignalEventDO> selectByStrategy(String strategyName, int limit) {
        return selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SignalEventDO>()
                .eq(SignalEventDO::getStrategyName, strategyName)
                .orderByDesc(SignalEventDO::getSignalTime)
                .last("LIMIT " + limit));
    }
}
