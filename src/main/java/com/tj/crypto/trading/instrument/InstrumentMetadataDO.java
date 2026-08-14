package com.tj.crypto.trading.instrument;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Persistence projection for a versioned instrument rule set. */
@Data
@TableName("instrument_metadata")
public class InstrumentMetadataDO {
    @TableId(type = IdType.AUTO)
    private Long metadataId;
    private String exchange;
    private String marketType;
    private String symbol;
    private String venueSymbol;
    private String baseAsset;
    private String quoteAsset;
    private String settleAsset;
    private String instrumentStatus;
    private BigDecimal tickSize;
    private BigDecimal stepSize;
    private BigDecimal minQuantity;
    private BigDecimal maxQuantity;
    private BigDecimal minNotional;
    private BigDecimal contractMultiplier;
    private BigDecimal makerFeeRate;
    private BigDecimal takerFeeRate;
    private Integer maxLeverage;
    private BigDecimal maintenanceMarginRate;
    private Integer fundingIntervalHours;
    private Long validFromMs;
    private Long validToMs;
    private String sourceVersion;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
