package com.tj.crypto.observability.slo;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Runtime window and threshold configuration for trading SLO calculations. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "crypto.slo")
public class SloProperties {
    private long windowMs = 3_600_000;
    private long snapshotIntervalMs = 60_000;
    private long paperOrderLatencyThresholdMs = 500;
    private long marketDataFreshnessThresholdMs = 10_000;
}
