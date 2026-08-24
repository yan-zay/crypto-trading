package com.tj.crypto.trading.venue.contract;

/**
 * Detached owner attestation. It never carries the venue key, secret, passphrase or signed request.
 * AVAILABLE is only a claim: a certification runner must validate the signature with a separately
 * configured trusted verifier before any owner-controlled probe can become VERIFIED.
 */
public record CredentialAttestation(State state, String issuer, String profileSha256,
                                    String evidenceReference, long attestedAtMs,
                                    long expiresAtMs, String signatureBase64, String reason) {
    public CredentialAttestation {
        if (state == null) throw new IllegalArgumentException("state is required");
        issuer = issuer == null ? "" : issuer.trim();
        profileSha256 = profileSha256 == null ? "" : profileSha256.trim();
        evidenceReference = evidenceReference == null ? "" : evidenceReference.trim();
        signatureBase64 = signatureBase64 == null ? "" : signatureBase64.trim();
        reason = reason == null ? "" : reason.trim();
        if (state == State.AVAILABLE) {
            if (issuer.isBlank() || evidenceReference.isBlank()
                    || !VenueContractEvidenceDigest.isSha256(profileSha256)
                    || signatureBase64.isBlank()) {
                throw new IllegalArgumentException(
                        "Available credentials require issuer, profile digest, evidence and signature");
            }
            if (attestedAtMs <= 0 || expiresAtMs <= attestedAtMs) {
                throw new IllegalArgumentException("Available credential attestation requires a valid TTL");
            }
        } else if (state != State.NOT_APPLICABLE && reason.isBlank()) {
            throw new IllegalArgumentException("Unavailable credentials require a reason");
        }
    }

    public static CredentialAttestation signedAvailable(String issuer, String profileSha256,
                                                        String evidenceReference,
                                                        long attestedAtMs, long expiresAtMs,
                                                        String signatureBase64) {
        return new CredentialAttestation(State.AVAILABLE, issuer, profileSha256,
                evidenceReference, attestedAtMs, expiresAtMs, signatureBase64, "");
    }

    public static CredentialAttestation notVerified(String reason) {
        return new CredentialAttestation(State.NOT_VERIFIED, "", "", "",
                0, 0, "", reason);
    }

    public static CredentialAttestation notApplicable() {
        return new CredentialAttestation(State.NOT_APPLICABLE, "", "", "",
                0, 0, "", "");
    }

    public boolean availableAt(long nowMs) {
        return state == State.AVAILABLE && attestedAtMs <= nowMs && expiresAtMs > nowMs;
    }

    /** Canonical payload signed by the trusted evidence issuer. */
    public String signedPayload() {
        return signedPayload(issuer, profileSha256, evidenceReference, attestedAtMs, expiresAtMs);
    }

    public static String signedPayload(String issuer, String profileSha256,
                                       String evidenceReference, long attestedAtMs,
                                       long expiresAtMs) {
        if (issuer == null || profileSha256 == null || evidenceReference == null) {
            throw new IllegalArgumentException("Signed payload fields are required");
        }
        return "issuer=" + issuer.length() + ":" + issuer
                + ";profileSha256=" + profileSha256.length() + ":" + profileSha256
                + ";evidenceReference=" + evidenceReference.length() + ":" + evidenceReference
                + ";attestedAtMs=" + attestedAtMs
                + ";expiresAtMs=" + expiresAtMs + ";";
    }

    public enum State { AVAILABLE, NOT_VERIFIED, REVOKED, NOT_APPLICABLE }
}
