package com.tj.crypto.marketdata.model;

import com.tj.crypto.common.domain.Exchange;

/**
 * 事件元数据，不可变值对象。
 * 记录事件的来源、时间戳和追踪信息。
 *
 * @param source             数据来源交易所
 * @param exchangeTimestamp  交易所时间戳（毫秒）
 * @param receivedTimestamp  本地接收时间戳（毫秒）
 * @param rawMessageId       原始消息标识（用于去重/追踪）
 */
public record EventMetadata(
        Exchange source,
        long exchangeTimestamp,
        long receivedTimestamp,
        String rawMessageId
) {
    /**
     * 便捷工厂方法，自动填充本地接收时间。
     */
    public static EventMetadata of(Exchange source, long exchangeTimestamp) {
        return new EventMetadata(source, exchangeTimestamp, System.currentTimeMillis(), null);
    }

    /**
     * 便捷工厂方法，带原始消息 ID。
     */
    public static EventMetadata of(Exchange source, long exchangeTimestamp, String rawMessageId) {
        return new EventMetadata(source, exchangeTimestamp, System.currentTimeMillis(), rawMessageId);
    }
}
