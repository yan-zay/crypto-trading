package com.tj.crypto.research.agent;

import java.util.List;

/** 当前运行时因子注册表的只读快照。 */
public record FactorCatalogSnapshot(
        int registeredCount,
        List<FactorView> factors) {

    public record FactorView(String name, boolean supportsExplicitBarHistory) {
    }
}
