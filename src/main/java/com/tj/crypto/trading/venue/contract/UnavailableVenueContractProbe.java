package com.tj.crypto.trading.venue.contract;

/** Probe used to emit an explicit NOT_VERIFIED report without touching any network endpoint. */
public final class UnavailableVenueContractProbe implements VenueContractProbe {
    private final VenueContractProfile profile;
    private final CredentialAttestation credentialAttestation;

    public UnavailableVenueContractProbe(VenueContractProfile profile, String reason) {
        this.profile = java.util.Objects.requireNonNull(profile, "profile");
        this.credentialAttestation = CredentialAttestation.notVerified(reason);
    }

    @Override
    public Kind kind() {
        return profile.ownerControlledEnvironment() ? Kind.OWNER_REMOTE : Kind.OFFLINE_REPLAY;
    }

    @Override
    public VenueContractProfile profile() {
        return profile;
    }

    @Override
    public CredentialAttestation credentialAttestation() {
        return credentialAttestation;
    }

    @Override
    public VenueContractObservation observe(VenueContractScenario scenario) {
        throw new IllegalStateException("Unavailable owner credentials prohibit contract probes");
    }
}
