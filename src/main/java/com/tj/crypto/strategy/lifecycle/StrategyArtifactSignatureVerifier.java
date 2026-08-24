package com.tj.crypto.strategy.lifecycle;

import java.time.Instant;

public interface StrategyArtifactSignatureVerifier {
    ArtifactVerification verify(SignedStrategyArtifact artifact, Instant now);
}
