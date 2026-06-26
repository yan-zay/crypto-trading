package com.tj.crypto.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @Author zay
 * @Date 2025/9/17 17:45
 */
@Getter
@AllArgsConstructor
public enum Symbol {

    BTC_USDT("btcusdt", "111"),
    ETH_USDT("ethusdt", "222"),
    SOL_USDT("solusdt", "333"),
    DOGE_USDT("dogeusdt", "444"),
    ;

    private final String value;
    private final String desc;
}
