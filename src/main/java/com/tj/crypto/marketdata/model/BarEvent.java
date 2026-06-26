package com.tj.crypto.marketdata.model;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.Timeframe;

import java.math.BigDecimal;

/**
 * K 线事件，不可变值对象。
 * 由 Binance kline stream 解析而来。
 *
 * @param instrument   交易工具
 * @param metadata     事件元数据
 * @param timeframe    时间周期
 * @param open         开盘价
 * @param high         最高价
 * @param low          最低价
 * @param close        收盘价
 * @param volume       基础资产成交量
 * @param quoteVolume  计价资产成交量
 * @param closed       是否为完整 K 线（Binance 在 K 线未完成时也会推送更新）
 */
public record BarEvent(
        Instrument instrument,
        EventMetadata metadata,
        Timeframe timeframe,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume,
        BigDecimal quoteVolume,
        boolean closed
) implements MarketEvent {}
