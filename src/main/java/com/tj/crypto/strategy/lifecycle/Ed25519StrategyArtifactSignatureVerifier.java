package com.tj.crypto.strategy.lifecycle;

import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;

/** Verifies artifacts only against explicitly provisioned, non-revoked Ed25519 keys. */
@Component
public class Ed25519StrategyArtifactSignatureVerifier implements StrategyArtifactSignatureVerifier {
    private final TrustedSigningKeyProvider trustedKeys;

    public Ed25519StrategyArtifactSignatureVerifier(TrustedSigningKeyProvider trustedKeys) {
        this.trustedKeys = Objects.requireNonNull(trustedKeys, "trustedKeys");
    }

    @Override
    public ArtifactVerification verify(SignedStrategyArtifact artifact, Instant now) {
        if (artifact == null || now == null) throw new IllegalArgumentException("artifact and now are required");
        if (!artifact.contentAddressMatches()) {
            return ArtifactVerification.denied(LifecycleReason.ARTIFACT_HASH_MISMATCH,
                    "artifact bytes do not match the declared SHA-256");
        }
        if (!artifact.expiresAt().isAfter(now)) {
            return ArtifactVerification.denied(LifecycleReason.ARTIFACT_EXPIRED, "artifact signature has expired");
        }
        if (artifact.signedAt().isAfter(now)) {
            return ArtifactVerification.denied(LifecycleReason.ARTIFACT_SIGNATURE_INVALID,
                    "artifact signature timestamp is in the future");
        }
        if (!"ED25519".equals(artifact.signatureAlgorithm().toUpperCase(Locale.ROOT))) {
            return ArtifactVerification.denied(LifecycleReason.ARTIFACT_SIGNATURE_INVALID,
                    "only Ed25519 strategy signatures are accepted");
        }
        TrustedSigningKey key = trustedKeys.find(artifact.signingKeyId()).orElse(null);
        if (key == null || !"ED25519".equals(key.algorithm().toUpperCase(Locale.ROOT))
                || !key.trustedAt(now) || !key.trustedAt(artifact.signedAt())) {
            return ArtifactVerification.denied(LifecycleReason.SIGNING_KEY_NOT_TRUSTED,
                    "signing key is missing, inactive, expired, or revoked");
        }
        try {
            PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(key.publicKeyX509Base64())));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(artifact.signaturePayload());
            if (!verifier.verify(Base64.getDecoder().decode(artifact.signatureBase64()))) {
                return ArtifactVerification.denied(LifecycleReason.ARTIFACT_SIGNATURE_INVALID,
                        "artifact signature verification failed");
            }
            return ArtifactVerification.accepted();
        } catch (Exception invalid) {
            return ArtifactVerification.denied(LifecycleReason.ARTIFACT_SIGNATURE_INVALID,
                    "artifact signature encoding is invalid");
        }
    }
}
