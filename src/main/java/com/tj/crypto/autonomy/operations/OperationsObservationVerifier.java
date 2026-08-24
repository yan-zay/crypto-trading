package com.tj.crypto.autonomy.operations;

/**
 * Read-only trust boundary. Implementations verify signatures/issuer/audience and recompute the
 * attestation hash; they must not use process-local cache as authorization.
 */
@FunctionalInterface
public interface OperationsObservationVerifier {
    OperationsObservationVerification verify(OperationsObservation observation, long nowMs);
}
