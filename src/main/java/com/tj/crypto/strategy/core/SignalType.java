package com.tj.crypto.strategy.core;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 交易信号类型枚举。
 */
@Getter
@AllArgsConstructor
public enum SignalType {
    BUY("buy", "买入"),
    SELL("sell", "卖出"),
    HOLD("hold", "持有"),
    ;

    private final String code;
    private final String displayName;
}
