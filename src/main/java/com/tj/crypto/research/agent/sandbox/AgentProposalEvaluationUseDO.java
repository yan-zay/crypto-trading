package com.tj.crypto.research.agent.sandbox;

import lombok.Data;

@Data
public class AgentProposalEvaluationUseDO {
    private String proposalId;
    private String proposalUseSha256;
    private String targetEnvironment;
    private String strategyName;
    private String strategyArtifactSha256;
    private String releaseAssignmentId;
    private String governanceSubjectSha256;
    private String instrumentScopeSha256;
    private String activatedBy;
    private Long activatedAtMs;
    private Long expiresAtMs;
    private Boolean liveExecutionAuthorized;
    private String proposalJson;
}
