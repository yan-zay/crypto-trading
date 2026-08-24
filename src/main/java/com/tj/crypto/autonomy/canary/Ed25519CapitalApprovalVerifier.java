package com.tj.crypto.autonomy.canary;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;

/** Verifies capital approvals against independently provisioned, time-bounded Ed25519 public keys. */
public final class Ed25519CapitalApprovalVerifier implements CapitalApprovalVerifier {
    private final CapitalApprovalKeyProvider trustedKeys;

    public Ed25519CapitalApprovalVerifier(CapitalApprovalKeyProvider trustedKeys) {
        this.trustedKeys = Objects.requireNonNull(trustedKeys, "trustedKeys");
    }

    @Override
    public CapitalApprovalVerification verify(CapitalStepApproval approval, Instant now) {
        if (approval == null || now == null) throw new IllegalArgumentException("approval and now are required");
        if (!approval.contentAddressMatches()) return CapitalApprovalVerification.CONTENT_DIGEST_MISMATCH;
        if (!CanaryCanonical.isSha256(approval.evidenceSha256())
                || !CanaryCanonical.isSha256(approval.policySha256())
                || !CanaryCanonical.isSha256(approval.deploymentScopeSha256())
                || !CanaryCanonical.isSha256(approval.metricPairSha256())
                || !approval.expiresAt().isAfter(approval.observedAt())) {
            return CapitalApprovalVerification.MALFORMED;
        }
        if (approval.observedAt().isAfter(now)) return CapitalApprovalVerification.NOT_YET_VALID;
        if (!approval.expiresAt().isAfter(now)) return CapitalApprovalVerification.EXPIRED;
        if (!"ED25519".equals(approval.signatureAlgorithm().toUpperCase(Locale.ROOT))) {
            return CapitalApprovalVerification.INVALID_SIGNATURE;
        }
        TrustedCapitalApprovalKey key = trustedKeys.findByIssuer(approval.issuer()).orElse(null);
        if (key == null || !"ED25519".equals(key.algorithm())
                || !key.trustedAt(approval.observedAt()) || !key.trustedAt(now)) {
            return CapitalApprovalVerification.UNTRUSTED_ISSUER;
        }
        try {
            PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(key.publicKeyX509Base64())));
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(publicKey);
            signature.update(approval.signaturePayload());
            return signature.verify(Base64.getDecoder().decode(approval.signatureBase64()))
                    ? CapitalApprovalVerification.VERIFIED
                    : CapitalApprovalVerification.INVALID_SIGNATURE;
        } catch (Exception invalid) {
            return CapitalApprovalVerification.INVALID_SIGNATURE;
        }
    }
}
