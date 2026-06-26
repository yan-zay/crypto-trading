package com.tj.crypto.marketdata.model;

import com.tj.crypto.common.domain.Instrument;

import java.math.BigDecimal;

/**
 * 持仓量事件，不可变值对象。
 * 由 Coinglass open interest API 解析而来。
 *
 * @param instrument       交易工具
 * @param metadata         事件元数据
 * @param openInterest     持仓量（基础资产）
 * @param openInterestUsd  持仓量（USD 计价）
 */
public record OpenInterestEvent(
        Instrument instrument,
        EventMetadata metadata,
        BigDecimal openInterest,
        BigDecimal openInterestUsd
) implements MarketEvent {}
