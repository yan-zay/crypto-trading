package com.tj.crypto.strategy.lifecycle;

import java.time.Instant;

/** Trust adapter for immutable experiment-registry evidence; never reads registry mappers directly. */
public interface RegisteredResearchEvidenceProvider {
    RegisteredResearchEvidence assess(SignedStrategyArtifact artifact,
                                      StrategyEvidenceType evidenceType,
                                      Instant now);
}
