package com.tj.crypto.autonomy.capital;

import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Closed set of pinned Ed25519 policy-signing keys. */
public final class CapitalPolicyTrustRoot {
    private final Map<String, TrustedPolicyKey> keys;

    public CapitalPolicyTrustRoot(List<TrustedPolicyKey> trustedKeys) {
        Objects.requireNonNull(trustedKeys, "trustedKeys");
        if (trustedKeys.isEmpty()) {
            throw new IllegalArgumentException("at least one trusted policy key is required");
        }
        LinkedHashMap<String, TrustedPolicyKey> indexed = new LinkedHashMap<>();
        for (TrustedPolicyKey key : trustedKeys) {
            Objects.requireNonNull(key, "trustedPolicyKey");
            if (indexed.put(key.keyId(), key) != null) {
                throw new IllegalArgumentException("duplicate trusted policy key: " + key.keyId());
            }
        }
        this.keys = Collections.unmodifiableMap(indexed);
    }

    PolicySignatureVerification verify(SignedCapitalRiskPolicy signed, Instant now) {
        Objects.requireNonNull(signed, "signed");
        Objects.requireNonNull(now, "now");
        TrustedPolicyKey key = keys.get(signed.keyId());
        if (key == null) return PolicySignatureVerification.UNKNOWN_KEY;
        if (!key.currentAt(now)) return PolicySignatureVerification.KEY_NOT_CURRENT;
        if (!key.currentAt(signed.policy().issuedAt())) {
            return PolicySignatureVerification.POLICY_ISSUED_OUTSIDE_KEY_VALIDITY;
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key.publicKey());
            verifier.update(signed.policy().canonicalBytes());
            return verifier.verify(signed.signature())
                    ? PolicySignatureVerification.VERIFIED
                    : PolicySignatureVerification.INVALID_SIGNATURE;
        } catch (GeneralSecurityException invalid) {
            return PolicySignatureVerification.INVALID_SIGNATURE;
        }
    }
}
