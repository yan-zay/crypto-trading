package com.tj.crypto.admin.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 系统状态 DTO。
 * 聚合系统运行时间、策略数量、因子数量等关键指标。
 */
@Data
@Builder
public class SystemStatusDTO {

    /** 应用启动时间戳（毫秒） */
    private long startupTimestamp;

    /** 运行时长（毫秒） */
    private long uptimeMs;

    /** 已注册策略数量 */
    private int strategyCount;

    /** 已注册因子计算器数量 */
    private int factorCount;

    /** 已收集信号总数 */
    private int totalSignalCount;

    /** 数据连接器数量 */
    private int connectorCount;

    /** 已连接的连接器数量 */
    private int connectedConnectorCount;
}
