package com.tj.crypto.research.experiment;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExperimentFamilyRegistrationTest {
    @Test
    void requiresACompleteContiguousFamilyAndCoreImmutableArtifacts() {
        assertThatThrownBy(() -> new ExperimentFamilyRegistration(
                "key", "name", "hypothesis", "protocol-v1", "NONE", "NONE", 2,
                artifacts(), List.of(trial("trial-0", 0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complete trial family size");

        assertThatThrownBy(() -> new ExperimentFamilyRegistration(
                "key", "name", "hypothesis", "protocol-v1", "NONE", "NONE", 2,
                artifacts(), List.of(trial("trial-0", 0), trial("trial-2", 2))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contiguous");

        List<ExperimentArtifactReference> missingContainer = artifacts().stream()
                .filter(artifact -> artifact.kind() != ExperimentArtifactKind.CONTAINER)
                .toList();
        assertThatThrownBy(() -> new ExperimentFamilyRegistration(
                "key", "name", "hypothesis", "protocol-v1", "NONE", "NONE", 1,
                missingContainer, List.of(trial("trial-0", 0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one CONTAINER");
    }

    @Test
    void rejectsNonContentAddressedArtifactsAndParameters() {
        assertThatThrownBy(() -> new ExperimentArtifactReference("dataset",
                ExperimentArtifactKind.DATASET, "v1", "s3://bucket/data", "latest",
                "application/x-parquet"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHA-256");

        assertThatThrownBy(() -> new ExperimentTrialDefinition(
                "trial", 0, "hypothesis", 7L, "mutable"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHA-256");
    }

    private static List<ExperimentArtifactReference> artifacts() {
        return List.of(
                artifact("dataset", ExperimentArtifactKind.DATASET, 'a'),
                artifact("code", ExperimentArtifactKind.CODE, 'b'),
                artifact("container", ExperimentArtifactKind.CONTAINER, 'c'),
                artifact("config", ExperimentArtifactKind.CONFIG, 'd'),
                artifact("dependencies", ExperimentArtifactKind.DEPENDENCY_LOCK, 'f'),
                artifact("model", ExperimentArtifactKind.MODEL, "NONE", '1'),
                artifact("prompt", ExperimentArtifactKind.PROMPT, "NONE", '2'));
    }

    private static ExperimentArtifactReference artifact(String id, ExperimentArtifactKind kind,
                                                        char digest) {
        return artifact(id, kind, "v1", digest);
    }

    private static ExperimentArtifactReference artifact(String id, ExperimentArtifactKind kind,
                                                        String version, char digest) {
        return new ExperimentArtifactReference(id, kind, version, "immutable://" + id,
                String.valueOf(digest).repeat(64), "application/octet-stream");
    }

    private static ExperimentTrialDefinition trial(String id, int ordinal) {
        return new ExperimentTrialDefinition(id, ordinal, "falsifiable hypothesis", 7L,
                "e".repeat(64));
    }
}
