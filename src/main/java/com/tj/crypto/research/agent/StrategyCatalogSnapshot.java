package com.tj.crypto.research.agent;

import java.util.List;

/** 当前运行时策略注册表的只读快照。 */
public record StrategyCatalogSnapshot(
        int registeredCount,
        int enabledCount,
        List<StrategyView> strategies) {

    public record StrategyView(
            String name,
            boolean enabled,
            List<String> listenedEvents) {
    }
}
