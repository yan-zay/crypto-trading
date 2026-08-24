package com.tj.crypto.autonomy.lineage;

/** Capital allocation, hard-risk and execution policy versions used by one decision. */
public record DecisionPolicyLineage(
        PolicyArtifactReference capitalPolicy,
        PolicyArtifactReference riskPolicy,
        PolicyArtifactReference executionPolicy) {

    public DecisionPolicyLineage {
        if (capitalPolicy == null || riskPolicy == null || executionPolicy == null) {
            throw new IllegalArgumentException("capital, risk and execution policies are required");
        }
    }
}
