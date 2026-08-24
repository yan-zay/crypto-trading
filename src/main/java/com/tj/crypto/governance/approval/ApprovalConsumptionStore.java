package com.tj.crypto.governance.approval;

/** Durable compare-and-set boundary; true means this exact request was consumed for the first time. */
@FunctionalInterface
public interface ApprovalConsumptionStore {
    boolean tryConsume(ApprovalConsumption consumption);
}
