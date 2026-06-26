package com.tj.crypto.marketdata.quality;

import java.util.List;

/**
 * 数据质量报告，不可变值对象。
 * 汇总 K 线数据的质量检查结果。
 *
 * @param totalBars       检查的 K 线总数
 * @param gapCount        检测到的时间间隙数
 * @param duplicateCount  检测到的重复数据数
 * @param anomalyCount    检测到的异常数据数
 * @param issues          具体问题描述列表
 */
public record DataQualityReport(
        int totalBars,
        int gapCount,
        int duplicateCount,
        int anomalyCount,
        List<String> issues
) {
    /**
     * 是否存在任何质量问题。
     */
    public boolean hasIssues() {
        return gapCount > 0 || duplicateCount > 0 || anomalyCount > 0;
    }

    /**
     * 创建一个无问题的报告。
     */
    public static DataQualityReport clean(int totalBars) {
        return new DataQualityReport(totalBars, 0, 0, 0, List.of());
    }
}
