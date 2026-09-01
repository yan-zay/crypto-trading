package com.tj.crypto.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "crypto.agent.model")
public class AgentModelProperties {
    private boolean enabled;
    private String endpoint = "";
    private String bearerToken = "";
    private String modelVersion = "";
    private String promptVersion = "";
    private List<String> allowedHosts = new ArrayList<>();
    private int callTimeoutMs = 30_000;
    private int maxResponseBytes = 262_144;
    private String evalReportPath = "";
    private String evalReportSha256 = "";
    private long maxEvalAgeMs = 7L * 24 * 60 * 60 * 1000;
}
