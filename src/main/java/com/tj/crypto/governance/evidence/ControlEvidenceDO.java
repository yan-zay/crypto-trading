package com.tj.crypto.governance.evidence;

import lombok.Data;

@Data
public class ControlEvidenceDO {
    private String evidenceId;
    private String controlKind;
    private String scope;
    private String environment;
    private String subjectSha256;
    private String policyVersion;
    private String evidenceStatus;
    private String owner;
    private String issuer;
    private Long startedAtMs;
    private Long completedAtMs;
    private Long expiresAtMs;
    private Long registeredAtMs;
    private String artifactUri;
    private String artifactSha256;
    private String predecessorEvidenceId;
    private String notes;
}
