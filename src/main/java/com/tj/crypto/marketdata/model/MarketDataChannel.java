package com.tj.crypto.marketdata.model;

import com.tj.crypto.common.domain.ChannelType;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;

/**
 * 市场数据频道，不可变值对象。
 * 标识一个具体的数据订阅频道。
 *
 * @param exchange     交易所
 * @param marketType   市场类型
 * @param channelType  频道类型
 * @param symbol       交易对符号
 * @param timeframe    时间周期（仅 KLINE 类型需要，其他为 null）
 */
public record MarketDataChannel(
        Exchange exchange,
        MarketType marketType,
        ChannelType channelType,
        String symbol,
        Timeframe timeframe
) {}
