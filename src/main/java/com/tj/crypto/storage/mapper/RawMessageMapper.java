package com.tj.crypto.storage.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tj.crypto.mapper.BaseMapperX;
import com.tj.crypto.storage.entity.RawMessageDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 原始消息 Mapper。
 */
@Mapper
public interface RawMessageMapper extends BaseMapperX<RawMessageDO> {

    /**
     * 按 checksum 查询（去重检查）。
     */
    default RawMessageDO selectByChecksum(String checksum) {
        return selectOne(new LambdaQueryWrapper<RawMessageDO>()
                .eq(RawMessageDO::getChecksum, checksum));
    }

    /**
     * 按 source + symbol + 时间范围查询原始消息。
     */
    default List<RawMessageDO> selectBySourceAndTimeRange(String source, String symbol,
                                                           long fromTime, long toTime) {
        return selectList(new LambdaQueryWrapper<RawMessageDO>()
                .eq(RawMessageDO::getSource, source)
                .eq(RawMessageDO::getSymbol, symbol)
                .ge(RawMessageDO::getReceivedTime, fromTime)
                .le(RawMessageDO::getReceivedTime, toTime)
                .orderByAsc(RawMessageDO::getReceivedTime));
    }

    /**
     * 查询未处理的原始消息。
     */
    default List<RawMessageDO> selectUnprocessed(int limit) {
        return selectList(new LambdaQueryWrapper<RawMessageDO>()
                .eq(RawMessageDO::getProcessed, false)
                .orderByAsc(RawMessageDO::getReceivedTime)
                .last("LIMIT " + limit));
    }
}
