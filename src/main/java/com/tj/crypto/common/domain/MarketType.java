package com.tj.crypto.common.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 市场类型枚举。
 * 区分现货、期货、永续合约等不同市场。
 */
@Getter
@AllArgsConstructor
public enum MarketType {
    SPOT("spot", "现货"),
    FUTURES("futures", "期货"),
    PERPETUAL("perpetual", "永续合约"),
    ;

    private final String code;
    private final String displayName;
}
