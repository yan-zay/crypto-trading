package com.tj.crypto.strategy.lifecycle;

import lombok.Data;

@Data
class StrategyArtifactRow {
    private String artifactSha256;
    private String strategyName;
    private String artifactVersion;
    private byte[] artifactContent;
    private String mediaType;
    private String artifactUri;
    private String signatureAlgorithm;
    private String signingKeyId;
    private String signatureBase64;
    private Long signedAtMs;
    private Long expiresAtMs;
    private String submittedBy;
    private Long submittedAtMs;
    private String researchRegistrationId;
    private String researchRegistrationSha256;
}

@Data
class TrustedSigningKeyRow {
    private String keyId;
    private String algorithm;
    private String publicKeyX509Base64;
    private String status;
    private Long validFromMs;
    private Long expiresAtMs;
    private Long revokedAtMs;
}

@Data
class StrategyLifecycleStateRow {
    private String strategyName;
    private String artifactSha256;
    private String previousArtifactSha256;
    private String stage;
    private Long reviewCycle;
    private Long stateVersion;
    private String updatedBy;
    private Long updatedAtMs;
    private String policyReason;
}

@Data
class StrategyApprovalRow {
    private String approvalId;
    private String strategyName;
    private String artifactSha256;
    private Long reviewCycle;
    private String decision;
    private String approvalScopeSha256;
    private String evidenceSetSha256;
    private Long expiresAtMs;
    private String approvedBy;
    private Long approvedAtMs;
}

@Data
class StrategyEvidenceRow {
    private String snapshotId;
    private String strategyName;
    private String artifactSha256;
    private String evidenceType;
    private String outcome;
    private String evidenceSha256;
    private String evidenceUri;
    private Long capturedAtMs;
    private Long expiresAtMs;
    private String recordedBy;
    private Long recordedAtMs;
}

@Data
class StrategyLifecycleEventRow {
    private String eventId;
    private String strategyName;
    private String artifactSha256;
    private Long sequenceNo;
    private Long reviewCycle;
    private String fromStage;
    private String toStage;
    private String action;
    private Boolean accepted;
    private String reasonCode;
    private String detailMessage;
    private String operator;
    private String evidenceSetSha256;
    private Long occurredAtMs;
    private String previousEventSha256;
    private String eventSha256;
}

@Data
class StrategyReleaseAssignmentHeadRow {
    private String strategyName;
    private String currentAssignmentId;
    private Long currentAssignmentVersion;
}

@Data
class StrategyReleaseAssignmentRow {
    private String assignmentId;
    private String strategyName;
    private String artifactSha256;
    private String minimumStage;
    private String governanceScope;
    private String governanceEnvironment;
    private String accountScope;
    private String gitCommit;
    private String buildSha256;
    private String containerImageDigest;
    private String configurationSha256;
    private String datasetManifestSha256;
    private String keyReference;
    private String runId;
    private String policyVersion;
    private String governanceSubjectSha256;
    private Long expiresAtMs;
    private Long assignmentVersion;
    private String assignedBy;
    private Long assignedAtMs;
}
