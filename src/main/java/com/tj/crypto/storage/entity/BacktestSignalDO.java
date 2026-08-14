package com.tj.crypto.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("backtest_signal")
public class BacktestSignalDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String runId;
    private Integer sequenceNo;
    private Long signalTime;
    private String signalType;
    private BigDecimal confidence;
    private String reason;
    private String factorSnapshotJson;
}
