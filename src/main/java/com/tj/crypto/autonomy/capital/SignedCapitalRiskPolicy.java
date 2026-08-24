package com.tj.crypto.autonomy.capital;

import java.util.Objects;

/** Signed policy document. There is deliberately no caller-controlled verification flag. */
public final class SignedCapitalRiskPolicy {
    private final CapitalRiskPolicy policy;
    private final String keyId;
    private final byte[] signature;

    public SignedCapitalRiskPolicy(CapitalRiskPolicy policy, String keyId, byte[] signature) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.keyId = CapitalValues.id(keyId, "keyId", 192);
        Objects.requireNonNull(signature, "signature");
        if (signature.length != 64) {
            throw new IllegalArgumentException("Ed25519 signature must contain 64 bytes");
        }
        this.signature = signature.clone();
    }

    public CapitalRiskPolicy policy() {
        return policy;
    }

    public String keyId() {
        return keyId;
    }

    public byte[] signature() {
        return signature.clone();
    }
}
