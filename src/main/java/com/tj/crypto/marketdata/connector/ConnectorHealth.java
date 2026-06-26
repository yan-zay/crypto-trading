package com.tj.crypto.marketdata.connector;

/**
 * 连接器健康状态，不可变值对象。
 * 用于监控连接状态和诊断问题。
 *
 * @param connected            是否已连接
 * @param lastMessageTimestamp  最后收到消息的时间戳
 * @param messagesReceived     已收到的消息总数
 * @param reconnectCount       重连次数
 * @param lastError            最后一次错误信息
 */
public record ConnectorHealth(
        boolean connected,
        long lastMessageTimestamp,
        long messagesReceived,
        long reconnectCount,
        String lastError
) {
    /**
     * 创建初始健康状态。
     */
    public static ConnectorHealth initial() {
        return new ConnectorHealth(false, 0, 0, 0, null);
    }
}
