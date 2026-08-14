package com.tj.crypto.backtest.job;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BacktestJobDO {
    private String jobId;
    private String jobType;
    private String status;
    private String requestJson;
    private Integer progressPct;
    private String stage;
    private String resultId;
    private String errorCode;
    private String errorMessage;
    private Long randomSeed;
    private String createdBy;
    private Long createdAtMs;
    private Long startedAtMs;
    private Long completedAtMs;
    private Long heartbeatAtMs;
    private String workerId;
    private Long version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
