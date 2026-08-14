package com.tj.crypto.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("backtest_equity_point")
public class BacktestEquityPointDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String runId;
    private Integer sequenceNo;
    private Long eventTime;
    private BigDecimal equity;
}
