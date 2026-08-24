package com.tj.crypto.strategy.lifecycle;

public enum StrategyLifecycleAction {
    REGISTER_DRAFT,
    SUBMIT_REVIEW,
    RECORD_APPROVAL,
    APPROVE,
    START_SHADOW,
    START_CANARY,
    PROMOTE,
    ROLLBACK,
    RECORD_EVIDENCE,
    ASSIGN_RELEASE,
    POLICY_DOWNGRADE
}
