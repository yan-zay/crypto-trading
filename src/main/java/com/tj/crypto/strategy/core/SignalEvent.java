package com.tj.crypto.strategy.core;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.strategy.lifecycle.StrategyRuntimeRelease;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 交易信号事件，不可变值对象。
 * 由策略产出，表示一个交易决策。
 *
 * @param strategyName   策略名称
 * @param instrument     交易工具
 * @param type           信号类型（BUY/SELL/HOLD）
 * @param confidence     置信度（0-1）
 * @param reason         信号原因（人类可读）
 * @param factorSnapshot 生成信号时的因子快照
 * @param timestamp      信号时间戳
 */
public record SignalEvent(
        String strategyName,
        Instrument instrument,
        SignalType type,
        BigDecimal confidence,
        String reason,
        Map<String, BigDecimal> factorSnapshot,
        long timestamp,
        String releaseAssignmentId,
        String strategyArtifactSha256,
        String governanceSubjectSha256
) {
    /** Backtest and isolated strategy compatibility; managed runtime enriches before collection. */
    public SignalEvent(String strategyName, Instrument instrument, SignalType type,
                       BigDecimal confidence, String reason,
                       Map<String, BigDecimal> factorSnapshot, long timestamp) {
        this(strategyName, instrument, type, confidence, reason, factorSnapshot, timestamp,
                null, null, null);
    }

    public SignalEvent withRuntimeRelease(StrategyRuntimeRelease release) {
        return withRuntimeRelease(strategyName, release);
    }

    /** Bind lineage only when the signal identity matches the strategy whose release was resolved. */
    public SignalEvent withRuntimeRelease(String expectedStrategyName, StrategyRuntimeRelease release) {
        if (expectedStrategyName == null || expectedStrategyName.isBlank()
                || !expectedStrategyName.equals(strategyName)) {
            throw new IllegalArgumentException("signal strategyName does not match the resolved runtime strategy");
        }
        if (release == null || !release.lifecycleBound()) {
            throw new IllegalArgumentException("a lifecycle-bound runtime release is required");
        }
        return new SignalEvent(strategyName, instrument, type, confidence, reason,
                factorSnapshot, timestamp, release.assignmentId(), release.artifactSha256(),
                release.governanceSubjectSha256());
    }

    public boolean hasRuntimeReleaseLineage() {
        return releaseAssignmentId != null && strategyArtifactSha256 != null
                && governanceSubjectSha256 != null;
    }
}
