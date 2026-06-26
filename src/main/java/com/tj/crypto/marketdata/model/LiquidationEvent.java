package com.tj.crypto.marketdata.model;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.OrderSide;

import java.math.BigDecimal;

/**
 * 爆仓事件，不可变值对象。
 * 由 Coinglass 爆仓订单流或 Binance forceOrder stream 解析而来。
 *
 * @param instrument    交易工具
 * @param metadata      事件元数据
 * @param side          多/空方向
 * @param price         爆仓价格
 * @param quantity      爆仓数量（基础资产）
 * @param quantityUsd   爆仓金额（USD 计价）
 * @param exchangeName  原始交易所名称（Coinglass 可能聚合多交易所数据）
 */
public record LiquidationEvent(
        Instrument instrument,
        EventMetadata metadata,
        OrderSide side,
        BigDecimal price,
        BigDecimal quantity,
        BigDecimal quantityUsd,
        String exchangeName
) implements MarketEvent {}
