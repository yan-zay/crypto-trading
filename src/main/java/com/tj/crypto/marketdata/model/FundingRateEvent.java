package com.tj.crypto.marketdata.model;

import com.tj.crypto.common.domain.Instrument;

import java.math.BigDecimal;

/**
 * 资金费率事件，不可变值对象。
 * 由 Binance markPrice stream 或 Coinglass funding rate API 解析而来。
 *
 * @param instrument       交易工具
 * @param metadata         事件元数据
 * @param fundingRate      当前资金费率
 * @param predictedRate    预测资金费率
 * @param nextFundingTime  下次结算时间（毫秒时间戳）
 */
public record FundingRateEvent(
        Instrument instrument,
        EventMetadata metadata,
        BigDecimal fundingRate,
        BigDecimal predictedRate,
        long nextFundingTime
) implements MarketEvent {}
