package com.tj.crypto.strategy.core;

import com.tj.crypto.common.domain.Instrument;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 交易信号事件，不可变值对象。
 * 由策略产出，表示一个交易决策。
 *
 * @param strategyName   策略名称
 * @param instrument     交易工具
 * @param type           信号类型（BUY/SELL/HOLD）
 * @param confidence     置信度（0-1）
 * @param reason         信号原因（人类可读）
 * @param factorSnapshot 生成信号时的因子快照
 * @param timestamp      信号时间戳
 */
public record SignalEvent(
        String strategyName,
        Instrument instrument,
        SignalType type,
        BigDecimal confidence,
        String reason,
        Map<String, BigDecimal> factorSnapshot,
        long timestamp
) {}
