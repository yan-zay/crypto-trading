package com.tj.crypto.research.experiment;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Complete pre-registration command. Construction freezes order, content and declared
 * family size so a caller cannot silently omit a failed/undesirable trial later.
 */
public record ExperimentFamilyRegistration(
        String idempotencyKey,
        String experimentName,
        String hypothesis,
        String protocolVersion,
        String modelVersion,
        String promptVersion,
        int declaredTrialCount,
        List<ExperimentArtifactReference> artifacts,
        List<ExperimentTrialDefinition> trials
) {
    public ExperimentFamilyRegistration {
        idempotencyKey = ExperimentValidation.required(idempotencyKey, "idempotencyKey", 128);
        experimentName = ExperimentValidation.required(experimentName, "experimentName", 160);
        hypothesis = ExperimentValidation.required(hypothesis, "hypothesis", 2000);
        protocolVersion = ExperimentValidation.required(protocolVersion, "protocolVersion", 100);
        modelVersion = ExperimentValidation.required(modelVersion, "modelVersion", 160);
        promptVersion = ExperimentValidation.required(promptVersion, "promptVersion", 160);
        if (declaredTrialCount <= 0) {
            throw new IllegalArgumentException("declaredTrialCount must be positive");
        }
        if (artifacts == null || trials == null) {
            throw new IllegalArgumentException("artifacts and trials are required");
        }
        if (artifacts.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("artifacts must not contain null");
        }
        if (trials.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("trials must not contain null");
        }
        artifacts = artifacts.stream()
                .sorted(Comparator.comparing(ExperimentArtifactReference::artifactId))
                .toList();
        trials = trials.stream()
                .sorted(Comparator.comparingInt(ExperimentTrialDefinition::ordinal))
                .toList();
        if (trials.size() != declaredTrialCount) {
            throw new IllegalArgumentException("declaredTrialCount must equal the complete trial family size");
        }

        Set<String> artifactIds = new HashSet<>();
        Map<ExperimentArtifactKind, Integer> kindCounts = new EnumMap<>(ExperimentArtifactKind.class);
        for (ExperimentArtifactReference artifact : artifacts) {
            if (!artifactIds.add(artifact.artifactId())) {
                throw new IllegalArgumentException("duplicate artifactId: " + artifact.artifactId());
            }
            kindCounts.merge(artifact.kind(), 1, Integer::sum);
            if (artifact.kind() == ExperimentArtifactKind.MODEL
                    && !artifact.version().equals(modelVersion)) {
                throw new IllegalArgumentException("MODEL artifact version must equal modelVersion");
            }
            if (artifact.kind() == ExperimentArtifactKind.PROMPT
                    && !artifact.version().equals(promptVersion)) {
                throw new IllegalArgumentException("PROMPT artifact version must equal promptVersion");
            }
        }
        requireAtLeastOne(kindCounts, ExperimentArtifactKind.DATASET);
        requireExactlyOne(kindCounts, ExperimentArtifactKind.CODE);
        requireExactlyOne(kindCounts, ExperimentArtifactKind.CONTAINER);
        requireExactlyOne(kindCounts, ExperimentArtifactKind.CONFIG);
        requireExactlyOne(kindCounts, ExperimentArtifactKind.DEPENDENCY_LOCK);
        requireExactlyOne(kindCounts, ExperimentArtifactKind.MODEL);
        requireExactlyOne(kindCounts, ExperimentArtifactKind.PROMPT);

        Set<String> trialIds = new HashSet<>();
        Set<Integer> ordinals = new HashSet<>();
        for (ExperimentTrialDefinition trial : trials) {
            if (!trialIds.add(trial.trialId())) {
                throw new IllegalArgumentException("duplicate trialId: " + trial.trialId());
            }
            if (!ordinals.add(trial.ordinal())) {
                throw new IllegalArgumentException("duplicate trial ordinal: " + trial.ordinal());
            }
        }
        for (int ordinal = 0; ordinal < declaredTrialCount; ordinal++) {
            if (!ordinals.contains(ordinal)) {
                throw new IllegalArgumentException("trial ordinals must be contiguous from zero");
            }
        }
    }

    private static void requireAtLeastOne(Map<ExperimentArtifactKind, Integer> counts,
                                          ExperimentArtifactKind kind) {
        if (counts.getOrDefault(kind, 0) < 1) {
            throw new IllegalArgumentException("at least one " + kind + " artifact is required");
        }
    }

    private static void requireExactlyOne(Map<ExperimentArtifactKind, Integer> counts,
                                          ExperimentArtifactKind kind) {
        if (counts.getOrDefault(kind, 0) != 1) {
            throw new IllegalArgumentException("exactly one " + kind + " artifact is required");
        }
    }
}
