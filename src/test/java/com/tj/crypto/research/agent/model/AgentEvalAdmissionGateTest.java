package com.tj.crypto.research.agent.model;

import com.tj.crypto.config.properties.AgentModelProperties;
import com.tj.crypto.research.agent.eval.AgentEvalReport;
import com.tj.crypto.research.agent.eval.AgentEvalSubmission;
import com.tj.crypto.research.agent.eval.ImmutableAgentEvalReportStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentEvalAdmissionGateTest {

    @Test
    void admitsOnlyCurrentDigestBoundPassedReportForExactModel() {
        AgentModelProperties properties = new AgentModelProperties();
        properties.setEnabled(true);
        properties.setEvalReportPath("evidence/report.json");
        properties.setEvalReportSha256("a".repeat(64));
        properties.setMaxEvalAgeMs(10_000);
        ImmutableAgentEvalReportStore store = mock(ImmutableAgentEvalReportStore.class);
        AgentEvalReport report = mock(AgentEvalReport.class);
        AgentEvalReport.ReportBody body = mock(AgentEvalReport.ReportBody.class);
        AgentEvalSubmission submission = mock(AgentEvalSubmission.class);
        AgentEvalSubmission.Subject subject = mock(AgentEvalSubmission.Subject.class);
        when(store.read(java.nio.file.Path.of("evidence/report.json"))).thenReturn(report);
        when(report.reportSha256()).thenReturn("a".repeat(64));
        when(report.body()).thenReturn(body);
        when(body.status()).thenReturn(AgentEvalReport.EvaluationStatus.PASSED);
        when(body.evaluatedAtEpochMillis()).thenReturn(9_000L);
        when(body.agentSubmission()).thenReturn(submission);
        when(submission.subject()).thenReturn(subject);
        when(subject.modelVersion()).thenReturn("model-v1");
        AgentEvalAdmissionGate gate = new AgentEvalAdmissionGate(properties, store,
                Clock.fixed(Instant.ofEpochMilli(10_000), ZoneOffset.UTC));

        assertThat(gate.requirePassed("model-v1")).isEqualTo("a".repeat(64));
    }
}
