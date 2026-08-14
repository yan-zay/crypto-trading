package com.tj.crypto.storage.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tj.crypto.mapper.BaseMapperX;
import com.tj.crypto.storage.entity.BacktestRunDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface BacktestRunMapper extends BaseMapperX<BacktestRunDO> {
    default List<BacktestRunDO> selectRecent(int limit) {
        return selectList(new LambdaQueryWrapper<BacktestRunDO>()
                .orderByDesc(BacktestRunDO::getCreateTime)
                .last("LIMIT " + Math.max(1, Math.min(limit, 500))));
    }
}
