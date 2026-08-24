package com.tj.crypto.autonomy.capital;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Objects;

/** Pinned Ed25519 public key and its independently configured trust interval. */
public final class TrustedPolicyKey {
    private final String keyId;
    private final byte[] encodedPublicKey;
    private final Instant validFrom;
    private final Instant validUntil;

    public TrustedPolicyKey(String keyId, PublicKey publicKey,
                            Instant validFrom, Instant validUntil) {
        this.keyId = CapitalValues.id(keyId, "keyId", 192);
        Objects.requireNonNull(publicKey, "publicKey");
        if (!"EdDSA".equalsIgnoreCase(publicKey.getAlgorithm())
                && !"Ed25519".equalsIgnoreCase(publicKey.getAlgorithm())) {
            throw new IllegalArgumentException("only Ed25519 policy keys are supported");
        }
        byte[] encoded = publicKey.getEncoded();
        if (encoded == null || encoded.length == 0) {
            throw new IllegalArgumentException("publicKey must have X.509 encoding");
        }
        this.encodedPublicKey = encoded.clone();
        this.validFrom = Objects.requireNonNull(validFrom, "validFrom");
        this.validUntil = Objects.requireNonNull(validUntil, "validUntil");
        if (!validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("trusted key expiry must be after activation");
        }
        publicKey();
    }

    public String keyId() {
        return keyId;
    }

    public Instant validFrom() {
        return validFrom;
    }

    public Instant validUntil() {
        return validUntil;
    }

    public boolean currentAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return !validFrom.isAfter(instant) && validUntil.isAfter(instant);
    }

    PublicKey publicKey() {
        try {
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(encodedPublicKey));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Ed25519 is unavailable", impossible);
        } catch (InvalidKeySpecException invalid) {
            throw new IllegalArgumentException("invalid Ed25519 public key", invalid);
        }
    }
}
