package com.tj.crypto.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @Author zay
 * @Date 2025/9/17 17:45
 */
@Getter
@AllArgsConstructor
public enum Indicator {

    MA("ma", "111"),
    EMA("ema", "222"),
    LIQUIDATION ("liquidation", "333"),
    ;

    private final String value;
    private final String desc;
}
