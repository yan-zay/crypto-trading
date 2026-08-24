package com.tj.crypto.governance.evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.text.Normalizer;
import java.util.regex.Pattern;

/** Exact immutable deployment/research context to which external evidence is bound. */
public record GovernanceSubject(
        String scope,
        String environment,
        String accountScope,
        String gitCommit,
        String buildSha256,
        String containerImageDigest,
        String configurationSha256,
        String datasetManifestSha256,
        String strategyArtifactSha256,
        String keyReference,
        String runId,
        String policyVersion) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern GIT_COMMIT = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    public GovernanceSubject {
        scope = component(scope, "scope", 128);
        environment = component(environment, "environment", 32).toUpperCase(Locale.ROOT);
        accountScope = component(accountScope, "accountScope", 192);
        gitCommit = component(gitCommit, "gitCommit", 64).toLowerCase(Locale.ROOT);
        buildSha256 = hash(buildSha256, "buildSha256");
        containerImageDigest = component(containerImageDigest, "containerImageDigest", 96)
                .toLowerCase(Locale.ROOT);
        configurationSha256 = hash(configurationSha256, "configurationSha256");
        datasetManifestSha256 = hash(datasetManifestSha256, "datasetManifestSha256");
        strategyArtifactSha256 = hash(strategyArtifactSha256, "strategyArtifactSha256");
        keyReference = component(keyReference, "keyReference", 256);
        runId = component(runId, "runId", 128);
        policyVersion = component(policyVersion, "policyVersion", 96);
        if (!GIT_COMMIT.matcher(gitCommit).matches()) {
            throw new IllegalArgumentException("gitCommit must be a full lowercase Git object id");
        }
        if (!containerImageDigest.startsWith("sha256:")
                || !SHA_256.matcher(containerImageDigest.substring("sha256:".length())).matches()) {
            throw new IllegalArgumentException("containerImageDigest must be a sha256 digest");
        }
    }

    public String sha256() {
        String canonical = List.of(scope, environment, accountScope, gitCommit, buildSha256,
                        containerImageDigest, configurationSha256, datasetManifestSha256,
                        strategyArtifactSha256, keyReference, runId, policyVersion).stream()
                .map(value -> value.length() + ":" + value)
                .reduce("governance-subject-v1", (left, right) -> left + "|" + right);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String hash(String value, String name) {
        String normalized = component(value, name, 64).toLowerCase(Locale.ROOT);
        if (!SHA_256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256");
        }
        return normalized;
    }

    private static String component(String value, String name, int maxLength) {
        String normalized = value == null ? ""
                : Normalizer.normalize(value.trim(), Normalizer.Form.NFC);
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is required");
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " exceeds " + maxLength + " characters");
        }
        if (normalized.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException(name + " must not contain control characters");
        }
        return normalized;
    }
}
