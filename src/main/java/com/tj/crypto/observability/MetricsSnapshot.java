package com.tj.crypto.observability;

import com.tj.crypto.marketdata.connector.ConnectorHealth;

import java.util.Map;

/**
 * 系统指标快照，不可变值对象。
 * 由 SystemMetrics 生成，供 AlertService 检查。
 *
 * @param barEvents                  K 线事件计数
 * @param liquidationEvents          爆仓事件计数
 * @param fundingRateEvents          资金费率事件计数
 * @param openInterestEvents         持仓量事件计数
 * @param totalSignalCount           总信号数
 * @param connectorHealthMap         连接器健康状态
 * @param eventProcessingAvgMs       事件处理平均延迟（毫秒）
 * @param eventProcessingP99Ms       事件处理 P99 延迟（毫秒）
 * @param strategyExecutionAvgMs     策略执行平均耗时（毫秒）
 * @param strategyExecutionP99Ms     策略执行 P99 耗时（毫秒）
 * @param persistenceQueueDepth      持久化队列深度
 * @param memoryUsedPct              内存使用率（百分比）
 * @param errorRatePct               错误率（百分比）
 */
public record MetricsSnapshot(
        long barEvents,
        long liquidationEvents,
        long fundingRateEvents,
        long openInterestEvents,
        int totalSignalCount,
        Map<String, ConnectorHealth> connectorHealthMap,
        double eventProcessingAvgMs,
        double eventProcessingP99Ms,
        double strategyExecutionAvgMs,
        double strategyExecutionP99Ms,
        long persistenceQueueDepth,
        double memoryUsedPct,
        double errorRatePct
) {
}
