package com.tj.crypto.marketdata.connector;

import com.tj.crypto.common.domain.ChannelType;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;

/**
 * 订阅请求，不可变值对象。
 * 描述对某个数据频道的订阅意图。
 *
 * @param exchange     交易所
 * @param marketType   市场类型
 * @param channelType  频道类型
 * @param symbol       交易对符号
 * @param timeframe    时间周期（仅 KLINE 类型需要）
 */
public record SubscriptionRequest(
        Exchange exchange,
        MarketType marketType,
        ChannelType channelType,
        String symbol,
        Timeframe timeframe
) {}
