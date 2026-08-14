package com.tj.crypto.trading.paper.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("paper_position")
public class PaperPositionDO {
    @TableId(type = IdType.INPUT)
    private String positionId;
    private String accountId;
    private String exchange;
    private String marketType;
    private String symbol;
    private String side;
    private BigDecimal quantity;
    private BigDecimal entryPrice;
    private BigDecimal markPrice;
    private BigDecimal contractMultiplier;
    private Integer leverage;
    private String marginMode;
    private BigDecimal initialMargin;
    private BigDecimal maintenanceMargin;
    private BigDecimal openFee;
    private BigDecimal funding;
    private BigDecimal realizedPnl;
    private BigDecimal unrealizedPnl;
    private String strategyId;
    private String openOrderId;
    private Long openedAtMs;
    private Long updatedAtMs;
    private Long version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
