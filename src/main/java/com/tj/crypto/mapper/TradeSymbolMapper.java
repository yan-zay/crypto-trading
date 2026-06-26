package com.tj.crypto.mapper;

import com.tj.crypto.entity.TradeSymbolDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * @Author: zay
 * @Date: 2023/7/11 14:58
 */
@Mapper
public interface TradeSymbolMapper extends BaseMapperX<TradeSymbolDO> {


    @Update("UPDATE biz_tiny_id " +
            "SET max_id = #{maxId}, update_time = now(), version = version + 1 " +
            "WHERE id = #{param.id} AND max_id = #{param.maxId} AND version = #{param.version} AND biz_type = #{param.bizType}")
    int updateMaxId(@Param("maxId") Long maxId, @Param("param") TradeSymbolDO condition);
}
