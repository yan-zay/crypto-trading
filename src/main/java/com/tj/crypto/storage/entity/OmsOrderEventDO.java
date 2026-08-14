package com.tj.crypto.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** OMS 订单状态事件；事件只追加，不更新。 */
@Data
@TableName("oms_order_event")
public class OmsOrderEventDO {
    @TableId(type = IdType.ASSIGN_UUID)
    private String eventId;
    private String externalEventId;
    private String payloadChecksum;
    private String orderId;
    private String eventType;
    private String orderStatus;
    private Long eventTime;
    private BigDecimal fillPrice;
    private BigDecimal fillQuantity;
    private String rejectReason;
    private LocalDateTime createTime;
}
