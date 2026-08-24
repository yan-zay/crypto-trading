package com.tj.crypto.research.experiment;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ExperimentRegistrationDO {
    private String registrationId;
    private String idempotencyKey;
    private String registrationSha256;
    private String experimentName;
    private String hypothesis;
    private String protocolVersion;
    private String modelVersion;
    private String promptVersion;
    private Integer declaredTrialCount;
    private String registeredBy;
    private Long registeredAtMs;
    private LocalDateTime createTime;
}
