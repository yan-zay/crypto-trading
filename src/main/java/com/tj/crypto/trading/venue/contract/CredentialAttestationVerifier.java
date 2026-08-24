package com.tj.crypto.trading.venue.contract;

/** Trust boundary kept outside a probe so a probe cannot approve its own credential claim. */
@FunctionalInterface
public interface CredentialAttestationVerifier {
    Decision verify(VenueContractProfile profile, CredentialAttestation attestation, long nowMs);

    static CredentialAttestationVerifier unconfigured() {
        return (profile, attestation, nowMs) -> Decision.notVerified(
                "Trusted owner credential attestation verifier is not configured");
    }

    record Decision(Status status, String verifierId, String reason) {
        public Decision {
            if (status == null) throw new IllegalArgumentException("status is required");
            verifierId = verifierId == null ? "" : verifierId.trim();
            reason = reason == null ? "" : reason.trim();
            if (status == Status.VERIFIED && verifierId.isBlank()) {
                throw new IllegalArgumentException("Verified decisions require a verifier id");
            }
            if (status != Status.VERIFIED && reason.isBlank()) {
                throw new IllegalArgumentException("Non-verified decisions require a reason");
            }
        }

        public static Decision verified(String verifierId) {
            return new Decision(Status.VERIFIED, verifierId, "");
        }

        public static Decision notVerified(String reason) {
            return new Decision(Status.NOT_VERIFIED, "", reason);
        }

        public static Decision invalid(String verifierId, String reason) {
            return new Decision(Status.INVALID, verifierId, reason);
        }
    }

    enum Status { VERIFIED, NOT_VERIFIED, INVALID }
}
