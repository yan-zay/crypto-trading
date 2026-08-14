package com.tj.crypto.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Persisted trade belonging to one backtest run. */
@Data
@TableName("backtest_trade")
public class BacktestTradeDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String runId;
    private Integer sequenceNo;
    private String side;
    private BigDecimal quantity;
    private BigDecimal entryPrice;
    private BigDecimal exitPrice;
    private Long entryTime;
    private Long exitTime;
    private BigDecimal realizedPnl;
    private BigDecimal totalFee;
    private BigDecimal netPnl;
    private LocalDateTime createTime;
}
