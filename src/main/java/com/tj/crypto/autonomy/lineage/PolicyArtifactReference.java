package com.tj.crypto.autonomy.lineage;

/** Exact signed deterministic policy artifact. */
public record PolicyArtifactReference(
        String policyId,
        String policyVersion,
        String policyArtifactSha256) {

    public PolicyArtifactReference {
        policyId = OrderDecisionLineageValues.text(policyId, "policyId");
        policyVersion = OrderDecisionLineageValues.text(policyVersion, "policyVersion");
        policyArtifactSha256 = OrderDecisionLineageValues.digest(
                policyArtifactSha256, "policyArtifactSha256");
    }
}
