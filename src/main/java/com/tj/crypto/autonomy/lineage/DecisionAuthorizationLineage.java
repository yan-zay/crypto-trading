package com.tj.crypto.autonomy.lineage;

import java.time.Instant;

/** Independent approval and short-lived capability evidence; contains no authority material. */
public record DecisionAuthorizationLineage(
        String approvalId,
        String approvalSha256,
        String capabilityId,
        String capabilitySha256,
        String authorizationSubjectSha256,
        Instant capabilityNotBefore,
        Instant capabilityExpiresAt) {

    public DecisionAuthorizationLineage {
        approvalId = OrderDecisionLineageValues.text(approvalId, "approvalId");
        approvalSha256 = OrderDecisionLineageValues.digest(approvalSha256, "approvalSha256");
        capabilityId = OrderDecisionLineageValues.text(capabilityId, "capabilityId");
        capabilitySha256 = OrderDecisionLineageValues.digest(capabilitySha256, "capabilitySha256");
        authorizationSubjectSha256 = OrderDecisionLineageValues.digest(
                authorizationSubjectSha256, "authorizationSubjectSha256");
        capabilityNotBefore = OrderDecisionLineageValues.millisecondInstant(
                capabilityNotBefore, "capabilityNotBefore");
        capabilityExpiresAt = OrderDecisionLineageValues.millisecondInstant(
                capabilityExpiresAt, "capabilityExpiresAt");
        if (!capabilityExpiresAt.isAfter(capabilityNotBefore)) {
            throw new IllegalArgumentException("capability must have a positive validity window");
        }
    }
}
