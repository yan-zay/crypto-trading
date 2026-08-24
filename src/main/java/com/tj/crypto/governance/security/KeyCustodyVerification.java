package com.tj.crypto.governance.security;

import java.util.List;

/** Result of an independent live query to the configured KMS/Vault/HSM trust boundary. */
public record KeyCustodyVerification(boolean trusted,
                                     String verifierId,
                                     KeyCustodyAttestation attestation,
                                     List<String> blockers) {
    public KeyCustodyVerification {
        verifierId = verifierId == null ? "" : verifierId.trim();
        blockers = List.copyOf(blockers == null ? List.of() : blockers);
        if (trusted && (verifierId.isEmpty() || attestation == null || !blockers.isEmpty())) {
            throw new IllegalArgumentException(
                    "trusted key custody verification requires verifier identity and attestation");
        }
    }

    public static KeyCustodyVerification untrusted(String reason) {
        String blocker = reason == null || reason.isBlank()
                ? "KMS/Vault/HSM attestation is NOT_VERIFIED" : reason.trim();
        return new KeyCustodyVerification(false, "UNCONFIGURED", null, List.of(blocker));
    }
}
