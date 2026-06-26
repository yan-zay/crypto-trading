package com.tj.crypto.common.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 交易所枚举。
 * 每个值代表一个独立的交易所数据源。
 */
@Getter
@AllArgsConstructor
public enum Exchange {
    BINANCE("binance", "Binance"),
    COINGLASS("coinglass", "Coinglass"),
    OKX("okx", "OKX"),
    BYBIT("bybit", "Bybit"),
    ;

    private final String code;
    private final String displayName;
}
