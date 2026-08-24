package com.tj.crypto.marketdata.model;

/** 可进入统一不可变归档的 canonical 事件或原始公共行情消息。 */
public sealed interface ArchivableMarketData permits MarketEvent, RawMarketMessage {
    EventMetadata metadata();
}
