package com.tj.crypto.governance.security;

import com.tj.crypto.governance.evidence.GovernanceSubject;

/**
 * Trust boundary for key custody.
 *
 * <p>An implementation must query an allowlisted KMS/Vault/HSM control plane using its own
 * workload identity, validate provider signatures/audit records and recompute the supplied
 * evidence artifact hash. It must not copy booleans supplied by an API caller.</p>
 */
@FunctionalInterface
public interface KeyCustodyVerifier {
    KeyCustodyVerification verify(GovernanceSubject subject,
                                  String evidenceArtifactSha256,
                                  long nowMs);
}
