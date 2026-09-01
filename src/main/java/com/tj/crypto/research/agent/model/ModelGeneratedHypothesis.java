package com.tj.crypto.research.agent.model;

import com.tj.crypto.research.agent.sandbox.FalsifiableHypothesis;

/** Model trace and validated L1 artifact; it contains no execution capability. */
public record ModelGeneratedHypothesis(
        FalsifiableHypothesis hypothesis,
        String modelVersion,
        String promptVersion,
        String requestSha256,
        String responseSha256,
        String outputSha256,
        String evalReportSha256,
        long latencyMs,
        long costMicrousd,
        boolean executionAuthorized) {

    public ModelGeneratedHypothesis {
        if (hypothesis == null || executionAuthorized) {
            throw new IllegalArgumentException("validated non-executable hypothesis is required");
        }
    }
}
