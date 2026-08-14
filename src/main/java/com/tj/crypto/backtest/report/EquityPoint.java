package com.tj.crypto.backtest.report;

import java.math.BigDecimal;

/** 某一市场事件时点的账户净值快照。 */
public record EquityPoint(long timestamp, BigDecimal equity) {
}
