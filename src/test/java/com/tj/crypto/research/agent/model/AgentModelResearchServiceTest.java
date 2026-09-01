package com.tj.crypto.research.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.research.agent.sandbox.AgentAuditContext;
import com.tj.crypto.research.agent.sandbox.AgentResearchSandboxService;
import com.tj.crypto.research.agent.sandbox.FalsifiableHypothesis;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentModelResearchServiceTest {

    @Test
    void validatesStructuredOutputThroughSandboxAndBindsModelEvidence() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        StructuredAgentModelGateway gateway = mock(StructuredAgentModelGateway.class);
        AgentResearchSandboxService sandbox = mock(AgentResearchSandboxService.class);
        when(gateway.generate(any())).thenReturn(new StructuredModelResult(
                mapper.valueToTree(output()), "model-v1", "prompt-v1",
                "a".repeat(64), "b".repeat(64), "c".repeat(64),
                "d".repeat(64), 50, 100));
        FalsifiableHypothesis artifact = new FalsifiableHypothesis(
                "hyp-1", "title", "claim", "null", "BTCUSDT", List.of("x"),
                List.of("y"), "positive", "sharpe", ">1", "<=0", "buy-hold",
                List.of("volatility"), List.of("embargo"), List.of("manifest"),
                true, Instant.ofEpochMilli(1_000));
        when(sandbox.generateModelHypothesis(eq("session-1"), any(), any())).thenReturn(artifact);
        AgentModelResearchService service = new AgentModelResearchService(gateway, sandbox, mapper);

        ModelGeneratedHypothesis result = service.generateHypothesis("session-1",
                new ModelHypothesisRequest("Does x improve y?", "e".repeat(64),
                        List.of("x"), List.of("buy-hold")));

        assertThat(result.executionAuthorized()).isFalse();
        assertThat(result.hypothesis()).isEqualTo(artifact);
        ArgumentCaptor<AgentAuditContext> audit = ArgumentCaptor.forClass(AgentAuditContext.class);
        verify(sandbox).generateModelHypothesis(eq("session-1"), any(), audit.capture());
        assertThat(audit.getValue().modelOutputSha256()).isEqualTo("c".repeat(64));
        assertThat(audit.getValue().evalReportSha256()).isEqualTo("d".repeat(64));
    }

    private Map<String, Object> output() {
        return Map.ofEntries(
                Map.entry("title", "title"), Map.entry("falsifiableClaim", "claim"),
                Map.entry("nullHypothesis", "null"), Map.entry("researchUniverse", "BTCUSDT"),
                Map.entry("independentVariables", List.of("x")),
                Map.entry("dependentVariables", List.of("y")),
                Map.entry("expectedDirection", "positive"), Map.entry("evaluationMetric", "sharpe"),
                Map.entry("acceptanceThreshold", ">1"), Map.entry("rejectionRule", "<=0"),
                Map.entry("baseline", "buy-hold"), Map.entry("confounders", List.of("volatility")),
                Map.entry("leakageControls", List.of("embargo")),
                Map.entry("requiredDataManifests", List.of("manifest")));
    }
}
