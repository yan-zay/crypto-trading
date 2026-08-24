package com.tj.crypto.research.agent.sandbox;

import java.time.Instant;
import java.util.List;

/** L1 的可审查研究制品；它不是策略、订单或配置。 */
public record FalsifiableHypothesis(
        String hypothesisId,
        String title,
        String falsifiableClaim,
        String nullHypothesis,
        String researchUniverse,
        List<String> independentVariables,
        List<String> dependentVariables,
        String expectedDirection,
        String evaluationMetric,
        String acceptanceThreshold,
        String rejectionRule,
        String baseline,
        List<String> confounders,
        List<String> leakageControls,
        List<String> requiredDataManifests,
        boolean failedResultMustBeRegistered,
        Instant createdAt) {
}
