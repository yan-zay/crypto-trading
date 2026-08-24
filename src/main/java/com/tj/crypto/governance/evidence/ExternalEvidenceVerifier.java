package com.tj.crypto.governance.evidence;

/**
 * Trust boundary for external evidence.
 *
 * <p>An implementation must retrieve bytes from an allowlisted immutable store, recompute the
 * declared SHA-256, validate the control-specific schema, verify a trusted issuer signature and
 * audience, trusted issuer/key status and revocation, and return exact claims bound to the
 * supplied evidence and governance subject. Implementations must never treat the caller's
 * {@link ControlEvidence#issuer()}, status or checksum as proof.</p>
 */
@FunctionalInterface
public interface ExternalEvidenceVerifier {
    EvidenceArtifactVerification verify(ControlEvidence evidence,
                                        GovernanceSubject subject,
                                        long nowMs);
}
