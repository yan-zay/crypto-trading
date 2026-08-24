package com.tj.crypto.autonomy.canary;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.StringJoiner;

/** Externally signed approval bound to exactly one adjacent capital transition and metric pair. */
public record CapitalStepApproval(
        String approvalId,
        String issuer,
        String policySha256,
        String deploymentScopeSha256,
        String strategyName,
        String championReleaseArtifactSha256,
        String challengerReleaseArtifactSha256,
        String evaluationWindowId,
        String metricPairSha256,
        String fromRungId,
        String toRungId,
        Instant observedAt,
        Instant expiresAt,
        String evidenceUri,
        String evidenceSha256,
        String signatureAlgorithm,
        String signatureBase64,
        String contentSha256
) {
    public CapitalStepApproval {
        approvalId = CanaryCanonical.required(approvalId, "approvalId");
        issuer = CanaryCanonical.required(issuer, "issuer");
        policySha256 = lowerRequired(policySha256, "policySha256");
        deploymentScopeSha256 = lowerRequired(deploymentScopeSha256, "deploymentScopeSha256");
        strategyName = CanaryCanonical.required(strategyName, "strategyName");
        championReleaseArtifactSha256 = lowerRequired(championReleaseArtifactSha256,
                "championReleaseArtifactSha256");
        challengerReleaseArtifactSha256 = lowerRequired(challengerReleaseArtifactSha256,
                "challengerReleaseArtifactSha256");
        evaluationWindowId = CanaryCanonical.required(evaluationWindowId, "evaluationWindowId");
        metricPairSha256 = lowerRequired(metricPairSha256, "metricPairSha256");
        fromRungId = CanaryCanonical.required(fromRungId, "fromRungId");
        toRungId = CanaryCanonical.required(toRungId, "toRungId");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        evidenceUri = CanaryCanonical.required(evidenceUri, "evidenceUri");
        evidenceSha256 = lowerRequired(evidenceSha256, "evidenceSha256");
        signatureAlgorithm = CanaryCanonical.required(signatureAlgorithm, "signatureAlgorithm")
                .toUpperCase(Locale.ROOT);
        signatureBase64 = CanaryCanonical.required(signatureBase64, "signatureBase64");
        contentSha256 = lowerRequired(contentSha256, "contentSha256");
    }

    public static CapitalStepApproval issue(String approvalId,
                                            String issuer,
                                            String policySha256,
                                            String deploymentScopeSha256,
                                            String strategyName,
                                            String championReleaseArtifactSha256,
                                            String challengerReleaseArtifactSha256,
                                            String evaluationWindowId,
                                            String metricPairSha256,
                                            String fromRungId,
                                            String toRungId,
                                            Instant observedAt,
                                            Instant expiresAt,
                                            String evidenceUri,
                                            String evidenceSha256,
                                            String signatureAlgorithm,
                                            String signatureBase64) {
        String canonicalSignatureAlgorithm = signatureAlgorithm.trim().toUpperCase(Locale.ROOT);
        String content = contentDigest(approvalId, issuer, policySha256, deploymentScopeSha256, strategyName,
                championReleaseArtifactSha256, challengerReleaseArtifactSha256, evaluationWindowId,
                metricPairSha256, fromRungId, toRungId, observedAt, expiresAt, evidenceUri,
                evidenceSha256, canonicalSignatureAlgorithm, signatureBase64);
        return new CapitalStepApproval(approvalId, issuer, policySha256, deploymentScopeSha256, strategyName,
                championReleaseArtifactSha256, challengerReleaseArtifactSha256, evaluationWindowId,
                metricPairSha256, fromRungId, toRungId, observedAt, expiresAt, evidenceUri,
                evidenceSha256, canonicalSignatureAlgorithm, signatureBase64, content);
    }

    public byte[] signaturePayload() {
        return unsignedPayload(approvalId, issuer, policySha256, deploymentScopeSha256, strategyName,
                championReleaseArtifactSha256, challengerReleaseArtifactSha256, evaluationWindowId,
                metricPairSha256, fromRungId, toRungId, observedAt, expiresAt, evidenceUri,
                evidenceSha256, signatureAlgorithm).getBytes(StandardCharsets.UTF_8);
    }

    public boolean contentAddressMatches() {
        return CanaryCanonical.isSha256(contentSha256)
                && contentSha256.equals(contentDigest(approvalId, issuer, policySha256,
                deploymentScopeSha256, strategyName,
                championReleaseArtifactSha256, challengerReleaseArtifactSha256, evaluationWindowId,
                metricPairSha256, fromRungId, toRungId, observedAt, expiresAt, evidenceUri,
                evidenceSha256, signatureAlgorithm, signatureBase64));
    }

    private static String contentDigest(String approvalId,
                                        String issuer,
                                        String policySha256,
                                        String deploymentScopeSha256,
                                        String strategyName,
                                        String championReleaseArtifactSha256,
                                        String challengerReleaseArtifactSha256,
                                        String evaluationWindowId,
                                        String metricPairSha256,
                                        String fromRungId,
                                        String toRungId,
                                        Instant observedAt,
                                        Instant expiresAt,
                                        String evidenceUri,
                                        String evidenceSha256,
                                        String signatureAlgorithm,
                                        String signatureBase64) {
        String unsigned = unsignedPayload(approvalId, issuer, policySha256, deploymentScopeSha256, strategyName,
                championReleaseArtifactSha256, challengerReleaseArtifactSha256, evaluationWindowId,
                metricPairSha256, fromRungId, toRungId, observedAt, expiresAt, evidenceUri,
                evidenceSha256, signatureAlgorithm);
        return CanaryCanonical.sha256(unsigned + "\nsignatureBase64=" + signatureBase64);
    }

    private static String unsignedPayload(String approvalId,
                                          String issuer,
                                          String policySha256,
                                          String deploymentScopeSha256,
                                          String strategyName,
                                          String championReleaseArtifactSha256,
                                          String challengerReleaseArtifactSha256,
                                          String evaluationWindowId,
                                          String metricPairSha256,
                                          String fromRungId,
                                          String toRungId,
                                          Instant observedAt,
                                          Instant expiresAt,
                                          String evidenceUri,
                                          String evidenceSha256,
                                          String signatureAlgorithm) {
        return new StringJoiner("\n")
                .add("schema=capital-step-approval/v1")
                .add("approvalId=" + approvalId)
                .add("issuer=" + issuer)
                .add("policySha256=" + policySha256)
                .add("deploymentScopeSha256=" + deploymentScopeSha256)
                .add("strategyName=" + strategyName)
                .add("championReleaseArtifactSha256=" + championReleaseArtifactSha256)
                .add("challengerReleaseArtifactSha256=" + challengerReleaseArtifactSha256)
                .add("evaluationWindowId=" + evaluationWindowId)
                .add("metricPairSha256=" + metricPairSha256)
                .add("fromRungId=" + fromRungId)
                .add("toRungId=" + toRungId)
                .add("observedAt=" + observedAt.toEpochMilli())
                .add("expiresAt=" + expiresAt.toEpochMilli())
                .add("evidenceUri=" + evidenceUri)
                .add("evidenceSha256=" + evidenceSha256)
                .add("signatureAlgorithm=" + signatureAlgorithm)
                .toString();
    }

    private static String lowerRequired(String value, String field) {
        return CanaryCanonical.required(value, field).toLowerCase(Locale.ROOT);
    }
}
