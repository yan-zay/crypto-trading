package com.tj.crypto.research.agent.sandbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** 可审计、可复现且不具备执行能力的 L1 实验计划。 */
public record ExperimentPlan(
        String planId,
        String hypothesisId,
        String datasetManifestId,
        String datasetSha256,
        String codeVersion,
        String configurationVersion,
        Instant trainFrom,
        Instant trainTo,
        Instant testFrom,
        Instant testTo,
        Duration purge,
        Duration embargo,
        long randomSeed,
        List<String> baselines,
        List<String> evaluationMetrics,
        List<String> passCriteria,
        List<String> failureCriteria,
        Duration maxRuntime,
        boolean failedResultMustBeRegistered,
        Instant createdAt) {
}
