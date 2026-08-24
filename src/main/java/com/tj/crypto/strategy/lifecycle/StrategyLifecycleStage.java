package com.tj.crypto.strategy.lifecycle;

/** Ordered, deterministic stages of a strategy release. */
public enum StrategyLifecycleStage {
    DRAFT,
    REVIEW,
    APPROVED,
    SHADOW,
    CANARY,
    PROMOTED,
    ROLLED_BACK
}
