package com.tj.crypto.research.experiment;

import lombok.Data;

import java.time.LocalDateTime;

/** Mutable compare-and-swap pointer for an otherwise append-only event stream. */
@Data
public class ExperimentChainHeadDO {
    private String registrationId;
    private Long lastEventSequence;
    private String lastEventSha256;
    private Long version;
    private LocalDateTime updateTime;
}
