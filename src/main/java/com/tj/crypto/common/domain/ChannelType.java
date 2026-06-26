package com.tj.crypto.common.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 数据频道类型枚举。
 * 标识市场数据的不同种类。
 */
@Getter
@AllArgsConstructor
public enum ChannelType {
    KLINE("kline", "K线"),
    LIQUIDATION("liquidation", "爆仓"),
    FUNDING_RATE("funding_rate", "资金费率"),
    OPEN_INTEREST("open_interest", "持仓量"),
    DEPTH("depth", "订单簿深度"),
    TICKER("ticker", "行情"),
    MARK_PRICE("mark_price", "标记价格"),
    ;

    private final String code;
    private final String displayName;
}
