package com.tj.crypto.governance.approval;

/**
 * Trust boundary implemented by an enterprise IdP/MFA adapter.
 *
 * <p>Before returning trusted, the adapter must verify issuer/signature/audience, recompute the
 * artifact hash, bind subject/request/action/resource/governance subject and atomically reserve
 * the challenge in a durable replay ledger until after the request expires.</p>
 */
@FunctionalInterface
public interface MfaAttestationVerifier {
    MfaAssertionVerification verify(MfaAttestation attestation,
                                    DualControlRequest request,
                                    long nowMs);
}
