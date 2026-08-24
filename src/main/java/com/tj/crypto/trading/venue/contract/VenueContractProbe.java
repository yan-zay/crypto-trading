package com.tj.crypto.trading.venue.contract;

/**
 * Black-box driver for an owner-controlled venue environment or an offline replay fixture.
 * Implementations own credential access; credentials must never be returned through this API.
 */
public interface VenueContractProbe {
    /** Provenance is explicit and cannot be inferred from a profile supplied by the probe. */
    Kind kind();

    VenueContractProfile profile();

    CredentialAttestation credentialAttestation();

    VenueContractObservation observe(VenueContractScenario scenario);

    enum Kind { OFFLINE_REPLAY, OWNER_REMOTE }
}
