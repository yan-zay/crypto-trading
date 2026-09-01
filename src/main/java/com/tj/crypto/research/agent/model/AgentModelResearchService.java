package com.tj.crypto.research.agent.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.research.agent.sandbox.AgentAuditContext;
import com.tj.crypto.research.agent.sandbox.AgentResearchSandboxService;
import com.tj.crypto.research.agent.sandbox.FalsifiableHypothesis;
import com.tj.crypto.research.agent.sandbox.FalsifiableHypothesisDraft;
import org.springframework.stereotype.Service;

/** Connects a gated structured model response to the existing deterministic L1 sandbox. */
@Service
public class AgentModelResearchService {
    private static final String HYPOTHESIS_SCHEMA = """
            {"type":"object","additionalProperties":false,
             "required":["title","falsifiableClaim","nullHypothesis","researchUniverse",
              "independentVariables","dependentVariables","expectedDirection","evaluationMetric",
              "acceptanceThreshold","rejectionRule","baseline","confounders","leakageControls",
              "requiredDataManifests"],
             "properties":{
              "title":{"type":"string"},"falsifiableClaim":{"type":"string"},
              "nullHypothesis":{"type":"string"},"researchUniverse":{"type":"string"},
              "independentVariables":{"type":"array","items":{"type":"string"}},
              "dependentVariables":{"type":"array","items":{"type":"string"}},
              "expectedDirection":{"type":"string"},"evaluationMetric":{"type":"string"},
              "acceptanceThreshold":{"type":"string"},"rejectionRule":{"type":"string"},
              "baseline":{"type":"string"},"confounders":{"type":"array","items":{"type":"string"}},
              "leakageControls":{"type":"array","items":{"type":"string"}},
              "requiredDataManifests":{"type":"array","items":{"type":"string"}}}}
            """;

    private final StructuredAgentModelGateway gateway;
    private final AgentResearchSandboxService sandbox;
    private final ObjectMapper mapper;
    private final JsonNode schema;

    public AgentModelResearchService(StructuredAgentModelGateway gateway,
                                     AgentResearchSandboxService sandbox,
                                     ObjectMapper mapper) {
        this.gateway = gateway;
        this.sandbox = sandbox;
        this.mapper = mapper;
        try {
            this.schema = mapper.readTree(HYPOTHESIS_SCHEMA);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("built-in hypothesis schema is invalid", impossible);
        }
    }

    public ModelGeneratedHypothesis generateHypothesis(
            String sessionId, ModelHypothesisRequest request) {
        StructuredModelResult result = gateway.generate(new StructuredModelRequest(
                "GENERATE_FALSIFIABLE_HYPOTHESIS", mapper.valueToTree(request), schema));
        FalsifiableHypothesisDraft draft;
        try {
            draft = mapper.treeToValue(result.output(), FalsifiableHypothesisDraft.class);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Model output violates the hypothesis contract", invalid);
        }
        FalsifiableHypothesis artifact = sandbox.generateModelHypothesis(sessionId, draft,
                new AgentAuditContext(result.requestSha256(), null,
                        result.modelVersion(), result.promptVersion(),
                        result.outputSha256(), result.evalReportSha256()));
        return new ModelGeneratedHypothesis(artifact, result.modelVersion(), result.promptVersion(),
                result.requestSha256(), result.responseSha256(), result.outputSha256(),
                result.evalReportSha256(), result.latencyMs(), result.costMicrousd(), false);
    }
}
