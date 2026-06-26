package com.tj.crypto.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tj.crypto.entity.base.PhysicsTimeBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * K 线数据实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bar_event")
public class BarEventDO extends PhysicsTimeBaseDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String exchange;
    private String marketType;
    private String symbol;
    private String timeframe;
    private Long openTime;
    private BigDecimal openPrice;
    private BigDecimal highPrice;
    private BigDecimal lowPrice;
    private BigDecimal closePrice;
    private BigDecimal volume;
    private BigDecimal quoteVolume;
}
