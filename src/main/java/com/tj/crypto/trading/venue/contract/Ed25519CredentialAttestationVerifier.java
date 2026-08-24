package com.tj.crypto.trading.venue.contract;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;

/** Verifies short-lived profile-bound attestations against pinned Ed25519 public keys. */
public final class Ed25519CredentialAttestationVerifier
        implements CredentialAttestationVerifier {
    private final Map<String, PublicKey> trustedIssuers;

    public Ed25519CredentialAttestationVerifier(Map<String, PublicKey> trustedIssuers) {
        if (trustedIssuers == null || trustedIssuers.isEmpty()) {
            throw new IllegalArgumentException("At least one trusted attestation issuer is required");
        }
        this.trustedIssuers = Map.copyOf(trustedIssuers);
    }

    @Override
    public Decision verify(VenueContractProfile profile, CredentialAttestation attestation,
                           long nowMs) {
        if (profile == null || attestation == null) {
            return Decision.notVerified("Owner credential attestation was not supplied");
        }
        if (attestation.state() != CredentialAttestation.State.AVAILABLE) {
            String reason = attestation.reason().isBlank()
                    ? "Owner credential attestation is not available" : attestation.reason();
            return Decision.notVerified(reason);
        }
        if (!attestation.availableAt(nowMs)) {
            return Decision.notVerified("Owner credential attestation is expired or not yet valid");
        }
        PublicKey trustedKey = trustedIssuers.get(attestation.issuer());
        if (trustedKey == null) {
            return Decision.notVerified("Attestation issuer is not in the trusted owner key set");
        }
        byte[] expectedProfile = profile.profileSha256().getBytes(StandardCharsets.US_ASCII);
        byte[] claimedProfile = attestation.profileSha256().getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expectedProfile, claimedProfile)) {
            return Decision.invalid(attestation.issuer(),
                    "Credential attestation is bound to a different venue profile");
        }
        try {
            byte[] signatureBytes = Base64.getDecoder().decode(attestation.signatureBase64());
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(trustedKey);
            verifier.update(attestation.signedPayload().getBytes(StandardCharsets.UTF_8));
            if (!verifier.verify(signatureBytes)) {
                return Decision.invalid(attestation.issuer(),
                        "Credential attestation signature is invalid");
            }
            return Decision.verified("ed25519:" + attestation.issuer());
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return Decision.invalid(attestation.issuer(),
                    "Credential attestation signature could not be verified");
        }
    }
}
