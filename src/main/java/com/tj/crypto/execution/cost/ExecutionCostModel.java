package com.tj.crypto.execution.cost;

/** Strategy interface for marketability, partial fill, spread and impact estimates. */
public interface ExecutionCostModel {
    ExecutionFillPlan plan(ExecutionCostRequest request);
}
