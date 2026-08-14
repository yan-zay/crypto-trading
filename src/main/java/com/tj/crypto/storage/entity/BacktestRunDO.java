package com.tj.crypto.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Persisted backtest run summary and reproducibility metadata. */
@Data
@TableName("backtest_run")
public class BacktestRunDO {
    @TableId(type = IdType.INPUT)
    private String runId;
    private String strategyName;
    private String strategyConfigJson;
    private String exchange;
    private String marketType;
    private String symbol;
    private String timeframe;
    private Long dataStartTime;
    private Long startTime;
    private Long endTime;
    private BigDecimal initialCapital;
    private BigDecimal finalCapital;
    private BigDecimal totalReturnPct;
    private BigDecimal annualizedReturnPct;
    private BigDecimal maxDrawdownPct;
    private BigDecimal winRatePct;
    private BigDecimal sharpeRatio;
    private BigDecimal sortinoRatio;
    private BigDecimal calmarRatio;
    private BigDecimal avgTradeDurationMs;
    private Integer totalTrades;
    private Integer signalCount;
    private Integer winningTrades;
    private Integer losingTrades;
    private Integer maxWinStreak;
    private Integer maxLoseStreak;
    private BigDecimal avgWin;
    private BigDecimal avgLoss;
    private BigDecimal profitFactor;
    private BigDecimal totalFees;
    private String monthlyReturnsJson;
    private String assumptionsJson;
    private String robustnessJson;
    private String reproducibilityJson;
    private String executionQualityJson;
    private LocalDateTime createTime;
}
