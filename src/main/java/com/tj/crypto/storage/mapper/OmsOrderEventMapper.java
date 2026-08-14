package com.tj.crypto.storage.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tj.crypto.mapper.BaseMapperX;
import com.tj.crypto.storage.entity.OmsOrderEventDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;

import java.util.List;

/** OMS 订单事件 Mapper。 */
@Mapper
public interface OmsOrderEventMapper extends BaseMapperX<OmsOrderEventDO> {
    @Insert("""
            INSERT IGNORE INTO oms_order_event
                (event_id, external_event_id, payload_checksum, order_id, event_type,
                 order_status, event_time, fill_price, fill_quantity, reject_reason)
            VALUES (#{eventId}, #{externalEventId}, #{payloadChecksum}, #{orderId}, #{eventType},
                    #{orderStatus}, #{eventTime}, #{fillPrice}, #{fillQuantity}, #{rejectReason})
            """)
    int insertExternalIgnore(OmsOrderEventDO event);

    default List<OmsOrderEventDO> selectByOrderId(String orderId) {
        return selectList(new LambdaQueryWrapper<OmsOrderEventDO>()
                .eq(OmsOrderEventDO::getOrderId, orderId)
                .orderByAsc(OmsOrderEventDO::getEventTime)
                .orderByAsc(OmsOrderEventDO::getCreateTime));
    }
}
