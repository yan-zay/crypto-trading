package com.tj.crypto.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tj.crypto.entity.base.PhysicsTimeBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 交易记录实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("trade_record")
public class TradeRecordDO extends PhysicsTimeBaseDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String exchange;
    private String marketType;
    private String symbol;
    private String side;
    private BigDecimal quantity;
    private BigDecimal entryPrice;
    private BigDecimal exitPrice;
    private Long entryTime;
    private Long exitTime;
    private BigDecimal realizedPnl;
    private BigDecimal totalFee;
    private BigDecimal netPnl;
    private String strategyId;
    private String orderId;
}
