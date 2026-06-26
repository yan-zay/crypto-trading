package com.tj.crypto.marketdata.quality;

import com.tj.crypto.marketdata.model.BarEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 数据质量检查器。
 * 检测 K 线数据中的间隙、重复和异常。
 *
 * 设计决策：
 * - 纯函数式设计，不持有状态
 * - 每个检查方法独立，可单独调用
 * - 返回不可变 DataQualityReport，线程安全
 */
@Slf4j
@Component
public class DataQualityChecker {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    /**
     * 检测缺失 K 线（时间间隙）。
     * 假设输入已按时间排序，通过相邻 K 线的时间差判断是否存在间隙。
     * 允许 1.5 倍时间周期的容差，避免因网络延迟导致的误报。
     *
     * @param bars 已按时间排序的 K 线列表
     * @return 数据质量报告
     */
    public DataQualityReport checkGaps(List<BarEvent> bars) {
        if (bars == null || bars.size() < 2) {
            return DataQualityReport.clean(bars == null ? 0 : bars.size());
        }

        List<String> issues = new ArrayList<>();
        int gapCount = 0;

        for (int i = 1; i < bars.size(); i++) {
            BarEvent prev = bars.get(i - 1);
            BarEvent curr = bars.get(i);

            if (prev.timeframe() != curr.timeframe()) {
                continue;
            }

            long expectedInterval = prev.timeframe().getMillis();
            long prevTime = prev.metadata().exchangeTimestamp();
            long currTime = curr.metadata().exchangeTimestamp();
            long actualGap = currTime - prevTime;

            // 允许 1.5 倍容差
            if (actualGap > expectedInterval * 1.5) {
                gapCount++;
                long missingBars = (actualGap / expectedInterval) - 1;
                issues.add("Gap detected: %d missing bar(s) between timestamp %d and %d for %s (%s)"
                        .formatted(missingBars, prevTime, currTime,
                                prev.instrument().symbol(), prev.timeframe()));
            }
        }

        if (gapCount > 0) {
            log.warn("Data quality: {} gap(s) detected in {} bars", gapCount, bars.size());
        }

        return new DataQualityReport(bars.size(), gapCount, 0, 0, issues);
    }

    /**
     * 检测重复 K 线。
     * 基于 (instrument, timeframe, exchangeTimestamp) 三元组去重。
     *
     * @param bars K 线列表
     * @return 数据质量报告
     */
    public DataQualityReport checkDuplicates(List<BarEvent> bars) {
        if (bars == null || bars.isEmpty()) {
            return DataQualityReport.clean(bars == null ? 0 : bars.size());
        }

        List<String> issues = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int duplicateCount = 0;

        for (BarEvent bar : bars) {
            String key = buildDuplicateKey(bar);
            if (!seen.add(key)) {
                duplicateCount++;
                issues.add("Duplicate bar: %s at timestamp %d (%s)"
                        .formatted(bar.instrument().symbol(),
                                bar.metadata().exchangeTimestamp(),
                                bar.timeframe()));
            }
        }

        if (duplicateCount > 0) {
            log.warn("Data quality: {} duplicate(s) detected in {} bars", duplicateCount, bars.size());
        }

        return new DataQualityReport(bars.size(), 0, duplicateCount, 0, issues);
    }

    /**
     * 检测异常 K 线数据。
     * 检查项：
     * - 负价格（open/high/low/close < 0）
     * - 零成交量
     * - high < low（价格逻辑错误）
     * - close 不在 [low, high] 范围内
     *
     * @param bars K 线列表
     * @return 数据质量报告
     */
    public DataQualityReport checkAnomalies(List<BarEvent> bars) {
        if (bars == null || bars.isEmpty()) {
            return DataQualityReport.clean(bars == null ? 0 : bars.size());
        }

        List<String> issues = new ArrayList<>();
        int anomalyCount = 0;

        for (int i = 0; i < bars.size(); i++) {
            BarEvent bar = bars.get(i);
            List<String> barIssues = checkSingleBarAnomalies(bar, i);
            if (!barIssues.isEmpty()) {
                anomalyCount++;
                issues.addAll(barIssues);
            }
        }

        if (anomalyCount > 0) {
            log.warn("Data quality: {} anomaly/anomalies detected in {} bars", anomalyCount, bars.size());
        }

        return new DataQualityReport(bars.size(), 0, 0, anomalyCount, issues);
    }

    /**
     * 执行全部质量检查，合并结果。
     *
     * @param bars K 线列表
     * @return 合并的数据质量报告
     */
    public DataQualityReport checkAll(List<BarEvent> bars) {
        DataQualityReport gapReport = checkGaps(bars);
        DataQualityReport dupReport = checkDuplicates(bars);
        DataQualityReport anomalyReport = checkAnomalies(bars);

        List<String> allIssues = new ArrayList<>();
        allIssues.addAll(gapReport.issues());
        allIssues.addAll(dupReport.issues());
        allIssues.addAll(anomalyReport.issues());

        int totalBars = bars == null ? 0 : bars.size();
        return new DataQualityReport(
                totalBars,
                gapReport.gapCount(),
                dupReport.duplicateCount(),
                anomalyReport.anomalyCount(),
                allIssues
        );
    }

    private List<String> checkSingleBarAnomalies(BarEvent bar, int index) {
        List<String> issues = new ArrayList<>();

        // 负价格检查
        if (hasNegativePrice(bar)) {
            issues.add("Anomaly [index=%d]: negative price detected for %s (open=%s, high=%s, low=%s, close=%s)"
                    .formatted(index, bar.instrument().symbol(),
                            bar.open(), bar.high(), bar.low(), bar.close()));
        }

        // 零成交量检查
        if (bar.volume() != null && bar.volume().compareTo(ZERO) == 0) {
            issues.add("Anomaly [index=%d]: zero volume for %s at timestamp %d"
                    .formatted(index, bar.instrument().symbol(),
                            bar.metadata().exchangeTimestamp()));
        }

        // high < low 检查
        if (bar.high() != null && bar.low() != null && bar.high().compareTo(bar.low()) < 0) {
            issues.add("Anomaly [index=%d]: high (%s) < low (%s) for %s"
                    .formatted(index, bar.high(), bar.low(), bar.instrument().symbol()));
        }

        // close 不在 [low, high] 范围内
        if (bar.close() != null && bar.high() != null && bar.low() != null) {
            if (bar.close().compareTo(bar.high()) > 0 || bar.close().compareTo(bar.low()) < 0) {
                issues.add("Anomaly [index=%d]: close (%s) out of range [%s, %s] for %s"
                        .formatted(index, bar.close(), bar.low(), bar.high(),
                                bar.instrument().symbol()));
            }
        }

        return issues;
    }

    private boolean hasNegativePrice(BarEvent bar) {
        return isNegative(bar.open()) || isNegative(bar.high())
                || isNegative(bar.low()) || isNegative(bar.close());
    }

    private boolean isNegative(BigDecimal value) {
        return value != null && value.compareTo(ZERO) < 0;
    }

    private String buildDuplicateKey(BarEvent bar) {
        return "%s_%s_%d".formatted(
                bar.instrument().symbol(),
                bar.timeframe(),
                bar.metadata().exchangeTimestamp());
    }
}
