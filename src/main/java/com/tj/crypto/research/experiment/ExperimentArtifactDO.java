package com.tj.crypto.research.experiment;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ExperimentArtifactDO {
    private String registrationId;
    private String artifactId;
    private String artifactKind;
    private String artifactVersion;
    private String artifactUri;
    private String artifactSha256;
    private String mediaType;
    private LocalDateTime createTime;
}
