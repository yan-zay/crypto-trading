package com.tj.crypto.research.agent.sandbox;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/** 将严格结构化输入确定性转换为 L1 研究制品，不运行模型、网络或外部进程。 */
final class AgentResearchArtifactFactory {

    FalsifiableHypothesis hypothesis(FalsifiableHypothesisDraft draft, Instant createdAt) {
        if (draft == null || createdAt == null) throw new IllegalArgumentException("draft and createdAt are required");
        MessageDigest digest = digest("structured-falsifiable-hypothesis-v1");
        update(digest, draft.title());
        update(digest, draft.falsifiableClaim());
        update(digest, draft.nullHypothesis());
        update(digest, draft.researchUniverse());
        update(digest, draft.independentVariables());
        update(digest, draft.dependentVariables());
        update(digest, draft.expectedDirection());
        update(digest, draft.evaluationMetric());
        update(digest, draft.acceptanceThreshold());
        update(digest, draft.rejectionRule());
        update(digest, draft.baseline());
        update(digest, draft.confounders());
        update(digest, draft.leakageControls());
        update(digest, draft.requiredDataManifests());
        String id = "hyp-" + HexFormat.of().formatHex(digest.digest());
        return new FalsifiableHypothesis(
                id,
                draft.title(),
                draft.falsifiableClaim(),
                draft.nullHypothesis(),
                draft.researchUniverse(),
                draft.independentVariables(),
                draft.dependentVariables(),
                draft.expectedDirection(),
                draft.evaluationMetric(),
                draft.acceptanceThreshold(),
                draft.rejectionRule(),
                draft.baseline(),
                draft.confounders(),
                draft.leakageControls(),
                draft.requiredDataManifests(),
                true,
                createdAt);
    }

    ExperimentPlan plan(ExperimentPlanDraft draft, Instant createdAt) {
        if (draft == null || createdAt == null) throw new IllegalArgumentException("draft and createdAt are required");
        MessageDigest digest = digest("structured-experiment-plan-v1");
        update(digest, draft.hypothesisId());
        update(digest, draft.datasetManifestId());
        update(digest, draft.datasetSha256());
        update(digest, draft.codeVersion());
        update(digest, draft.configurationVersion());
        update(digest, draft.trainFrom().toString());
        update(digest, draft.trainTo().toString());
        update(digest, draft.testFrom().toString());
        update(digest, draft.testTo().toString());
        update(digest, draft.purge().toString());
        update(digest, draft.embargo().toString());
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(draft.randomSeed()).array());
        update(digest, draft.baselines());
        update(digest, draft.evaluationMetrics());
        update(digest, draft.passCriteria());
        update(digest, draft.failureCriteria());
        update(digest, draft.maxRuntime().toString());
        String id = "plan-" + HexFormat.of().formatHex(digest.digest());
        return new ExperimentPlan(
                id,
                draft.hypothesisId(),
                draft.datasetManifestId(),
                draft.datasetSha256(),
                draft.codeVersion(),
                draft.configurationVersion(),
                draft.trainFrom(),
                draft.trainTo(),
                draft.testFrom(),
                draft.testTo(),
                draft.purge(),
                draft.embargo(),
                draft.randomSeed(),
                draft.baselines(),
                draft.evaluationMetrics(),
                draft.passCriteria(),
                draft.failureCriteria(),
                draft.maxRuntime(),
                true,
                createdAt);
    }

    String proposalId(PaperShadowProposalRequest request, String requestingOperator) {
        MessageDigest digest = digest("paper-shadow-proposal-v2");
        update(digest, requestingOperator);
        update(digest, request.approvalPayloadSha256(requestingOperator));
        update(digest, request.approval().approvalId());
        update(digest, request.approval().approvalArtifactSha256());
        update(digest, request.approval().approvedBy());
        update(digest, request.approval().approvedAt().toString());
        update(digest, request.approval().expiresAt().toString());
        return "proposal-" + HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest digest(String domain) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, domain);
            return digest;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void update(MessageDigest digest, List<String> values) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(values.size()).array());
        values.forEach(value -> update(digest, value));
    }

    private void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
