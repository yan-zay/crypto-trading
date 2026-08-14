package com.tj.crypto.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tj.crypto.entity.base.PhysicsTimeBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 策略信号实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("signal_event")
public class SignalEventDO extends PhysicsTimeBaseDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String strategyName;
    private String exchange;
    private String marketType;
    private String symbol;
    private String signalType;
    private BigDecimal confidence;
    private String reason;
    private String factorSnapshot;  // JSON 字符串
    private Long signalTime;
}
