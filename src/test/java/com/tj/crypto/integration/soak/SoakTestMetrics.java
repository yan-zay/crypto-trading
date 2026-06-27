package com.tj.crypto.integration.soak;

/**
 * Soak test 指标快照。
 * 记录某个时间点的系统状态和性能数据。
 *
 * @param eventsProcessed  已处理事件数
 * @param signalsGenerated 已生成信号数
 * @param avgLatencyMs     平均事件处理延迟（毫秒）
 * @param maxLatencyMs     最大事件处理延迟（毫秒）
 * @param heapUsedMB       当前堆内存使用量（MB）
 * @param threadCount      当前线程数
 * @param errors           累计错误数
 */
public record SoakTestMetrics(
        long eventsProcessed,
        long signalsGenerated,
        double avgLatencyMs,
        double maxLatencyMs,
        long heapUsedMB,
        int threadCount,
        long errors
) {
    /**
     * 格式化为可读的状态报告行。
     */
    public String toReportLine() {
        return String.format(
                "events=%d, signals=%d, avgLatency=%.2fms, maxLatency=%.2fms, heap=%dMB, threads=%d, errors=%d",
                eventsProcessed, signalsGenerated, avgLatencyMs, maxLatencyMs, heapUsedMB, threadCount, errors
        );
    }
}
