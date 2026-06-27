package com.tj.crypto.admin.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 系统总览 DTO。
 * 聚合连接状态、策略数量、因子数量、信号数量、风控状态等关键指标。
 */
@Data
@Builder
public class OverviewDTO {

    /** 应用启动时间戳（毫秒） */
    private long startupTimestamp;

    /** 运行时长（毫秒） */
    private long uptimeMs;

    /** 已注册策略数量 */
    private int strategyCount;

    /** 已启用策略数量 */
    private int enabledStrategyCount;

    /** 已注册因子数量 */
    private int factorCount;

    /** 已收集信号总数 */
    private int totalSignalCount;

    /** 连接器总数 */
    private int connectorCount;

    /** 已连接的连接器数量 */
    private int connectedConnectorCount;

    /** 连接器状态列表 */
    private List<ConnectorStatusDTO> connectors;

    /** 风控配置 */
    private RiskConfigDTO riskConfig;
}
