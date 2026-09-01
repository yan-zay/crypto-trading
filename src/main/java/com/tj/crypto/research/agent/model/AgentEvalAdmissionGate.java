package com.tj.crypto.research.agent.model;

import com.tj.crypto.config.properties.AgentModelProperties;
import com.tj.crypto.research.agent.eval.AgentEvalReport;
import com.tj.crypto.research.agent.eval.ImmutableAgentEvalReportStore;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;
import java.time.Clock;

/** Requires a current, content-addressed PASSED report before any external model call. */
@Component
public class AgentEvalAdmissionGate {
    private final AgentModelProperties properties;
    private final ImmutableAgentEvalReportStore store;
    private final Clock clock;

    @Autowired
    public AgentEvalAdmissionGate(AgentModelProperties properties) {
        this(properties, new ImmutableAgentEvalReportStore(), Clock.systemUTC());
    }

    AgentEvalAdmissionGate(AgentModelProperties properties,
                           ImmutableAgentEvalReportStore store, Clock clock) {
        this.properties = properties;
        this.store = store;
        this.clock = clock;
    }

    public String requirePassed(String modelVersion) {
        if (!properties.isEnabled()) throw new IllegalStateException("Agent model gateway is disabled");
        String expected = sha(properties.getEvalReportSha256(), "evalReportSha256");
        if (properties.getEvalReportPath() == null || properties.getEvalReportPath().isBlank()) {
            throw new IllegalStateException("Agent evaluation report path is required");
        }
        AgentEvalReport report = store.read(Path.of(properties.getEvalReportPath()));
        if (!expected.equals(report.reportSha256())) {
            throw new IllegalStateException("Agent evaluation report digest mismatch");
        }
        if (report.body().status() != AgentEvalReport.EvaluationStatus.PASSED) {
            throw new IllegalStateException("Agent evaluation report did not pass");
        }
        if (!modelVersion.equals(report.body().agentSubmission().subject().modelVersion())) {
            throw new IllegalStateException("Agent evaluation report belongs to another model version");
        }
        long evaluatedAt = report.body().evaluatedAtEpochMillis();
        long now = clock.millis();
        if (evaluatedAt > now || properties.getMaxEvalAgeMs() <= 0
                || now - evaluatedAt > properties.getMaxEvalAgeMs()) {
            throw new IllegalStateException("Agent evaluation report is not current");
        }
        return report.reportSha256();
    }

    private String sha(String value, String name) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException(name + " must be lowercase SHA-256");
        }
        return normalized;
    }
}
