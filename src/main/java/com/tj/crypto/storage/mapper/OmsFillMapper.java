package com.tj.crypto.storage.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tj.crypto.mapper.BaseMapperX;
import com.tj.crypto.storage.entity.OmsFillDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** OMS 成交明细 Mapper。 */
@Mapper
public interface OmsFillMapper extends BaseMapperX<OmsFillDO> {
    default List<OmsFillDO> selectByOrderId(String orderId) {
        return selectList(new LambdaQueryWrapper<OmsFillDO>()
                .eq(OmsFillDO::getOrderId, orderId)
                .orderByAsc(OmsFillDO::getFillTime));
    }

    default List<OmsFillDO> selectRecent(int limit) {
        return selectList(new LambdaQueryWrapper<OmsFillDO>()
                .orderByDesc(OmsFillDO::getFillTime)
                .last("LIMIT " + Math.max(1, Math.min(limit, 500))));
    }

    @Select("""
            SELECT * FROM oms_fill WHERE account_id=#{accountId}
            ORDER BY fill_time DESC LIMIT #{limit}
            """)
    List<OmsFillDO> selectByAccount(@Param("accountId") String accountId,
                                    @Param("limit") int limit);

    @Select("SELECT COALESCE(SUM(fill_quantity),0) FROM oms_fill WHERE order_id=#{orderId}")
    java.math.BigDecimal sumQuantityByOrder(@Param("orderId") String orderId);
}
