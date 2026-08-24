package com.tj.crypto.research.agent.sandbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** L1 只能规划可复现的离线实验，不能触发执行或发布。 */
public record ExperimentPlanDraft(
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
        Duration maxRuntime) {

    public ExperimentPlanDraft {
        hypothesisId = SandboxValues.text(hypothesisId, "hypothesisId");
        datasetManifestId = SandboxValues.text(datasetManifestId, "datasetManifestId");
        datasetSha256 = SandboxValues.text(datasetSha256, "datasetSha256");
        if (!datasetSha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("datasetSha256 must be a SHA-256 digest");
        }
        datasetSha256 = datasetSha256.toLowerCase();
        codeVersion = SandboxValues.text(codeVersion, "codeVersion");
        configurationVersion = SandboxValues.text(configurationVersion, "configurationVersion");
        requireOrdered(trainFrom, trainTo, "train");
        requireOrdered(testFrom, testTo, "test");
        if (testFrom.isBefore(trainTo)) {
            throw new IllegalArgumentException("Test window must not overlap the training window");
        }
        requireNonNegative(purge, "purge");
        requireNonNegative(embargo, "embargo");
        baselines = SandboxValues.texts(baselines, "baselines");
        evaluationMetrics = SandboxValues.texts(evaluationMetrics, "evaluationMetrics");
        passCriteria = SandboxValues.texts(passCriteria, "passCriteria");
        failureCriteria = SandboxValues.texts(failureCriteria, "failureCriteria");
        if (maxRuntime == null || maxRuntime.isZero() || maxRuntime.isNegative()
                || maxRuntime.compareTo(Duration.ofHours(6)) > 0) {
            throw new IllegalArgumentException("maxRuntime must be positive and no longer than six hours");
        }
    }

    private static void requireOrdered(Instant from, Instant to, String name) {
        if (from == null || to == null || !to.isAfter(from)) {
            throw new IllegalArgumentException(name + " window must be ordered");
        }
    }

    private static void requireNonNegative(Duration value, String name) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
