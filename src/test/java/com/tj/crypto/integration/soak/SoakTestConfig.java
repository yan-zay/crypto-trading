package com.tj.crypto.integration.soak;

import com.tj.crypto.common.domain.Instrument;

import java.util.List;

/**
 * Soak test 配置。
 * 控制长时间稳定性测试的运行参数。
 *
 * @param durationMinutes       测试持续时间（分钟）
 * @param eventIntervalMs       事件发布间隔（毫秒）
 * @param symbols               测试使用的交易对列表
 * @param checkIntervalSeconds  指标采集间隔（秒）
 */
public record SoakTestConfig(
        int durationMinutes,
        long eventIntervalMs,
        List<Instrument> symbols,
        int checkIntervalSeconds
) {
    /**
     * 短时测试配置（2 分钟，适合 CI）。
     */
    public static SoakTestConfig shortTest(List<Instrument> symbols) {
        return new SoakTestConfig(2, 50, symbols, 10);
    }

    /**
     * 长时间测试配置（默认 24 小时）。
     */
    public static SoakTestConfig longTest(List<Instrument> symbols, int durationMinutes) {
        return new SoakTestConfig(durationMinutes, 100, symbols, 60);
    }
}
