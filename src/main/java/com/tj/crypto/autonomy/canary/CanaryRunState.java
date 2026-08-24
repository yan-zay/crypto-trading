package com.tj.crypto.autonomy.canary;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.StringJoiner;

/** Trusted runtime state is expected to be supplied by an external durable state owner. */
public record CanaryRunState(
        String strategyName,
        DeploymentScope deploymentScope,
        String championReleaseArtifactSha256,
        String challengerReleaseArtifactSha256,
        CanaryStage stage,
        String currentRungId,
        long stateVersion,
        Instant observedAt,
        Instant expiresAt,
        String persistenceEvidenceSha256,
        String contentSha256
) {
    public CanaryRunState {
        strategyName = CanaryCanonical.required(strategyName, "strategyName");
        deploymentScope = Objects.requireNonNull(deploymentScope, "deploymentScope");
        championReleaseArtifactSha256 = CanaryCanonical.required(
                championReleaseArtifactSha256, "championReleaseArtifactSha256").toLowerCase(Locale.ROOT);
        challengerReleaseArtifactSha256 = CanaryCanonical.required(
                challengerReleaseArtifactSha256, "challengerReleaseArtifactSha256").toLowerCase(Locale.ROOT);
        stage = Objects.requireNonNull(stage, "stage");
        currentRungId = currentRungId == null ? "" : currentRungId.trim();
        if (championReleaseArtifactSha256.equals(challengerReleaseArtifactSha256)) {
            throw new IllegalArgumentException("champion and challenger must be different releases");
        }
        if (stateVersion < 0) throw new IllegalArgumentException("stateVersion cannot be negative");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        persistenceEvidenceSha256 = CanaryCanonical.required(
                persistenceEvidenceSha256, "persistenceEvidenceSha256").toLowerCase(Locale.ROOT);
        contentSha256 = CanaryCanonical.required(contentSha256, "contentSha256").toLowerCase(Locale.ROOT);
        if (stage == CanaryStage.SHADOW && !currentRungId.isEmpty()) {
            throw new IllegalArgumentException("shadow state cannot claim a capital rung");
        }
        if (stage == CanaryStage.CANARY && currentRungId.isEmpty()) {
            throw new IllegalArgumentException("canary state requires a capital rung");
        }
    }

    public static CanaryRunState shadow(String strategyName, DeploymentScope deploymentScope,
                                        String champion, String challenger,
                                        long stateVersion, Instant observedAt, Instant expiresAt,
                                        String persistenceEvidenceSha256) {
        String digest = digest(strategyName, deploymentScope.contentSha256(), champion, challenger,
                CanaryStage.SHADOW, "", stateVersion, observedAt, expiresAt, persistenceEvidenceSha256);
        return new CanaryRunState(strategyName, deploymentScope, champion, challenger,
                CanaryStage.SHADOW, "", stateVersion, observedAt, expiresAt,
                persistenceEvidenceSha256, digest);
    }

    public static CanaryRunState canary(String strategyName, DeploymentScope deploymentScope,
                                        String champion, String challenger,
                                        String rungId, long stateVersion, Instant observedAt, Instant expiresAt,
                                        String persistenceEvidenceSha256) {
        String digest = digest(strategyName, deploymentScope.contentSha256(), champion, challenger,
                CanaryStage.CANARY, rungId, stateVersion, observedAt, expiresAt, persistenceEvidenceSha256);
        return new CanaryRunState(strategyName, deploymentScope, champion, challenger, CanaryStage.CANARY,
                rungId, stateVersion, observedAt, expiresAt, persistenceEvidenceSha256, digest);
    }

    public boolean contentAddressMatches() {
        return deploymentScope.contentAddressMatches()
                && CanaryCanonical.isSha256(championReleaseArtifactSha256)
                && CanaryCanonical.isSha256(challengerReleaseArtifactSha256)
                && CanaryCanonical.isSha256(persistenceEvidenceSha256)
                && CanaryCanonical.isSha256(contentSha256)
                && expiresAt.isAfter(observedAt)
                && contentSha256.equals(digest(strategyName, deploymentScope.contentSha256(),
                championReleaseArtifactSha256, challengerReleaseArtifactSha256, stage, currentRungId,
                stateVersion, observedAt, expiresAt, persistenceEvidenceSha256));
    }

    private static String digest(String strategyName, String deploymentScopeSha256,
                                 String champion, String challenger, CanaryStage stage, String rungId,
                                 long stateVersion, Instant observedAt, Instant expiresAt,
                                 String persistenceEvidenceSha256) {
        return CanaryCanonical.sha256(new StringJoiner("\n")
                .add("schema=canary-run-state/v1")
                .add("strategyName=" + strategyName.trim())
                .add("deploymentScopeSha256=" + deploymentScopeSha256)
                .add("championReleaseArtifactSha256=" + champion.toLowerCase(Locale.ROOT))
                .add("challengerReleaseArtifactSha256=" + challenger.toLowerCase(Locale.ROOT))
                .add("stage=" + stage.name())
                .add("currentRungId=" + (rungId == null ? "" : rungId.trim()))
                .add("stateVersion=" + stateVersion)
                .add("observedAt=" + observedAt.toEpochMilli())
                .add("expiresAt=" + expiresAt.toEpochMilli())
                .add("persistenceEvidenceSha256=" + persistenceEvidenceSha256.toLowerCase(Locale.ROOT))
                .toString());
    }
}
