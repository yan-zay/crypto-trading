package com.tj.crypto.autonomy.agent;

/** 每个 L3/L4 建议必须携带的模型、上下文、评测和数据血缘。 */
public record AgentDecisionLineage(
        String modelVersion,
        String promptSha256,
        String contextSha256,
        String toolPolicyVersion,
        String traceRootSha256,
        String evalReportSha256,
        String dataManifestSha256) {
    public AgentDecisionLineage {
        modelVersion = AutonomyValues.text(modelVersion, "modelVersion");
        promptSha256 = AutonomyValues.digest(promptSha256, "promptSha256");
        contextSha256 = AutonomyValues.digest(contextSha256, "contextSha256");
        toolPolicyVersion = AutonomyValues.text(toolPolicyVersion, "toolPolicyVersion");
        traceRootSha256 = AutonomyValues.digest(traceRootSha256, "traceRootSha256");
        evalReportSha256 = AutonomyValues.digest(evalReportSha256, "evalReportSha256");
        dataManifestSha256 = AutonomyValues.digest(dataManifestSha256, "dataManifestSha256");
    }
}
