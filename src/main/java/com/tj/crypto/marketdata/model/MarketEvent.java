package com.tj.crypto.marketdata.model;

import com.tj.crypto.common.domain.Instrument;

/**
 * 市场事件密封接口。
 * 所有标准化的市场数据事件都实现此接口。
 *
 * 设计决策：
 * - 使用 sealed interface（Java 17）而非 abstract class，因为事件是纯数据，不需要继承行为
 * - 所有实现类使用 record，确保不可变性
 * - 编译器可在 switch 表达式中检查所有 case
 */
public sealed interface MarketEvent
        permits BarEvent, LiquidationEvent, FundingRateEvent, OpenInterestEvent {

    /**
     * 事件关联的交易工具。
     */
    Instrument instrument();

    /**
     * 事件元数据（来源、时间戳、追踪信息）。
     */
    EventMetadata metadata();
}
