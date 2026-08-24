package com.tj.crypto.autonomy.readiness;

/** Closed result type: only externally verified claims can satisfy the gate. */
public sealed interface EvidenceVerification permits
        VerifiedReadinessClaims, RejectedEvidenceVerification {
}
