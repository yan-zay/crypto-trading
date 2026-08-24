package com.tj.crypto.research.experiment;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ExperimentTrialDO {
    private String registrationId;
    private String trialId;
    private Integer trialOrdinal;
    private String hypothesis;
    private Long randomSeed;
    private String parameterSha256;
    private LocalDateTime createTime;
}
