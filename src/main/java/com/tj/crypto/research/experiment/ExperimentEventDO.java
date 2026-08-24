package com.tj.crypto.research.experiment;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ExperimentEventDO {
    private String eventId;
    private String registrationId;
    private String trialId;
    private String commandIdempotencyKey;
    private String commandSha256;
    private Long eventSequence;
    private String eventType;
    private String fromStatus;
    private String toStatus;
    private String reasonCode;
    private String detailMessage;
    private String observedProvenanceSha256;
    private String resultArtifactUri;
    private String resultArtifactVersion;
    private String resultArtifactSha256;
    private String recordedBy;
    private Long occurredAtMs;
    private String previousEventSha256;
    private String eventSha256;
    private LocalDateTime createTime;
}
