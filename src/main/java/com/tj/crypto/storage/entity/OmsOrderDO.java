package com.tj.crypto.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** OMS 订单最新状态快照。 */
@Data
@TableName("oms_order")
public class OmsOrderDO {
    @TableId(type = IdType.INPUT)
    private String orderId;
    private String clientOrderId;
    private String accountId;
    private String orderSource;
    private String venueOrderId;
    private String externalStatus;
    private String correlationId;
    private Integer leverage;
    private String marginMode;
    private Long stateVersion;
    private Long lastEventAtMs;
    private String strategyId;
    private String exchange;
    private String marketType;
    private String symbol;
    private String tradeSide;
    private String requestedSide;
    private String positionSide;
    private Boolean reduceOnly;
    private String orderType;
    private BigDecimal quantity;
    private BigDecimal price;
    private BigDecimal filledQuantity;
    private BigDecimal avgFillPrice;
    private String status;
    private String rejectReason;
    private Long createdAtMs;
    private Long submittedAtMs;
    private Long filledAtMs;
    private Long cancelledAtMs;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
