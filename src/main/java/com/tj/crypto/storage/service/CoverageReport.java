package com.tj.crypto.storage.service;

import java.util.List;

/**
 * 数据覆盖率报告，不可变值对象。
 * 记录某个 symbol + timeframe 在指定时间范围内的数据覆盖情况。
 *
 * @param symbol      交易对符号
 * @param timeframe   时间周期代码
 * @param expectedBars 期望的 K 线数量
 * @param actualBars   实际存在的 K 线数量
 * @param coveragePct  覆盖率百分比 (0.0 ~ 100.0)
 * @param gaps         缺失时间段列表
 */
public record CoverageReport(
        String symbol,
        String timeframe,
        long expectedBars,
        long actualBars,
        double coveragePct,
        List<TimeGap> gaps
) {
    /**
     * 时间间隙，表示一段缺失数据的时间范围。
     *
     * @param from 缺失起始时间（毫秒）
     * @param to   缺失结束时间（毫秒）
     */
    public record TimeGap(long from, long to) {
        /**
         * 缺失的 K 线数量。
         */
        public long missingBars(long intervalMillis) {
            return (to - from) / intervalMillis;
        }
    }
}
