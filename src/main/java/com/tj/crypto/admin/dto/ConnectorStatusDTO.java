package com.tj.crypto.admin.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 连接器状态 DTO。
 * 描述单个数据源连接器的运行状态。
 */
@Data
@Builder
public class ConnectorStatusDTO {

    /** 连接器名称（类名） */
    private String name;

    /** 是否已连接 */
    private boolean connected;

    /** 已接收消息总数 */
    private long messagesReceived;

    /** 重连次数 */
    private long reconnectCount;

    /** 最后收到消息的时间戳 */
    private long lastMessageTimestamp;

    /** 最后一次错误信息，无错误时为空字符串 */
    private String lastError;
}
