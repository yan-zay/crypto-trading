package com.tj.crypto.autonomy.readiness;

import java.time.Instant;

/**
 * External trust boundary for long-term readiness evidence.
 *
 * <p>A real adapter must fetch bytes from an allowlisted immutable store, recompute the digest,
 * validate the exact typed schema and audience, verify an allowed signature and non-revoked
 * issuer/key, and return exact claims bound to the supplied subject and level. Caller-declared
 * status, outcome, issuer or booleans are not proof.</p>
 */
@FunctionalInterface
public interface ExternalReadinessEvidenceVerifier {
    EvidenceVerification verify(ExternalEvidenceReference reference,
                                LongTermReadinessSubject subject,
                                AutonomyLevel level,
                                Instant now);

    /** Default trust boundary permanently rejects until an owner configures a real adapter. */
    static ExternalReadinessEvidenceVerifier rejecting() {
        return (reference, subject, level, now) -> RejectedEvidenceVerification.notVerified(
                "no trusted long-term external evidence verifier is configured");
    }
}
