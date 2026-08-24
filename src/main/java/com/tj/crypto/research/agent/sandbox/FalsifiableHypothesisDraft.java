package com.tj.crypto.research.agent.sandbox;

import java.util.List;

/** L1 输入必须已结构化；服务不接受自由文本命令。 */
public record FalsifiableHypothesisDraft(
        String title,
        String falsifiableClaim,
        String nullHypothesis,
        String researchUniverse,
        List<String> independentVariables,
        List<String> dependentVariables,
        String expectedDirection,
        String evaluationMetric,
        String acceptanceThreshold,
        String rejectionRule,
        String baseline,
        List<String> confounders,
        List<String> leakageControls,
        List<String> requiredDataManifests) {

    public FalsifiableHypothesisDraft {
        title = SandboxValues.text(title, "title");
        falsifiableClaim = SandboxValues.text(falsifiableClaim, "falsifiableClaim");
        nullHypothesis = SandboxValues.text(nullHypothesis, "nullHypothesis");
        researchUniverse = SandboxValues.text(researchUniverse, "researchUniverse");
        independentVariables = SandboxValues.texts(independentVariables, "independentVariables");
        dependentVariables = SandboxValues.texts(dependentVariables, "dependentVariables");
        expectedDirection = SandboxValues.text(expectedDirection, "expectedDirection");
        evaluationMetric = SandboxValues.text(evaluationMetric, "evaluationMetric");
        acceptanceThreshold = SandboxValues.text(acceptanceThreshold, "acceptanceThreshold");
        rejectionRule = SandboxValues.text(rejectionRule, "rejectionRule");
        baseline = SandboxValues.text(baseline, "baseline");
        confounders = SandboxValues.texts(confounders, "confounders");
        leakageControls = SandboxValues.texts(leakageControls, "leakageControls");
        requiredDataManifests = SandboxValues.texts(requiredDataManifests, "requiredDataManifests");
    }
}
