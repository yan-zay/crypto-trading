package com.tj.crypto.research.agent.policy;

import java.util.regex.Pattern;

/** L2 提案必须绑定的完整、不可变版本清单。 */
public record AgentArtifactVersions(
        String gitCommit,
        String codeArtifactSha256,
        String containerImageDigest,
        String dataManifestId,
        String dataManifestSha256,
        String configurationVersion,
        String strategyArtifactVersion,
        String agentRuntimeVersion,
        String modelVersion,
        String promptVersion,
        String policyVersion,
        long randomSeed) {

    private static final Pattern GIT_COMMIT = Pattern.compile("[0-9a-fA-F]{40,64}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern OCI_DIGEST = Pattern.compile("sha256:[0-9a-fA-F]{64}");

    public AgentArtifactVersions {
        gitCommit = requireMatch(gitCommit, "gitCommit", GIT_COMMIT);
        codeArtifactSha256 = requireMatch(codeArtifactSha256, "codeArtifactSha256", SHA256);
        containerImageDigest = requireMatch(containerImageDigest, "containerImageDigest", OCI_DIGEST);
        dataManifestId = requireText(dataManifestId, "dataManifestId");
        dataManifestSha256 = requireMatch(dataManifestSha256, "dataManifestSha256", SHA256);
        configurationVersion = requireText(configurationVersion, "configurationVersion");
        strategyArtifactVersion = requireText(strategyArtifactVersion, "strategyArtifactVersion");
        agentRuntimeVersion = requireText(agentRuntimeVersion, "agentRuntimeVersion");
        modelVersion = requireText(modelVersion, "modelVersion");
        promptVersion = requireText(promptVersion, "promptVersion");
        policyVersion = requireText(policyVersion, "policyVersion");
    }

    public void requirePolicyVersion(String expected) {
        if (!policyVersion.equals(expected)) {
            throw new AgentPolicyViolationException(
                    "POLICY_VERSION_MISMATCH", "Proposal policy version does not match the active policy");
        }
    }

    private static String requireMatch(String value, String name, Pattern pattern) {
        String normalized = requireText(value, name);
        if (!pattern.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " has an invalid immutable digest/version format");
        }
        return normalized.toLowerCase();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
