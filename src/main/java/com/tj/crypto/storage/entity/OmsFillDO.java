package com.tj.crypto.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 单次成交明细；一个订单可以产生多条 fill。 */
@Data
@TableName("oms_fill")
public class OmsFillDO {
    @TableId(type = IdType.ASSIGN_UUID)
    private String fillId;
    private String accountId;
    private String strategyId;
    private String orderId;
    private String eventId;
    private String exchangeTradeId;
    private BigDecimal fillPrice;
    private BigDecimal fillQuantity;
    private BigDecimal referencePrice;
    private BigDecimal arrivalPrice;
    private BigDecimal spreadBps;
    private BigDecimal impactBps;
    private BigDecimal slippageBps;
    private BigDecimal fee;
    private String feeCurrency;
    private String liquidityRole;
    private Long fillTime;
    private LocalDateTime createTime;
}
