package com.tj.crypto.trading.venue;

import lombok.Data;

@Data
class LiveGovernanceDecisionDO {
    private String decisionId;
    private String clientOrderId;
    private String commandSha256;
    private String exchange;
    private String accountId;
    private String strategyId;
    private String releaseAssignmentId;
    private Long releaseAssignmentVersion;
    private String strategyArtifactSha256;
    private String governanceSubjectSha256;
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
    private String evidenceSetSha256;
    private String evidenceReferences;
    private String approvalRequestId;
    private String requestedBy;
    private Long authorizedAtMs;
    private Long expiresAtMs;
}
