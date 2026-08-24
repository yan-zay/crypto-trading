package com.tj.crypto.governance.security;

import com.tj.crypto.governance.evidence.GovernanceSubject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Fail-closed policy over an externally issued KMS/Vault/HSM attestation. */
@Service
public class KeyCustodyPolicy {
    private static final long MAX_DRILL_AGE_MS = Duration.ofDays(90).toMillis();
    private static final long MAX_VERIFICATION_AGE_MS = Duration.ofMinutes(5).toMillis();
    private static final long MAX_ATTESTATION_TTL_MS = Duration.ofMinutes(15).toMillis();

    private final KeyCustodyVerifier verifier;

    @Autowired
    public KeyCustodyPolicy(KeyCustodyVerifier verifier) {
        this.verifier = verifier;
    }

    /** Compatibility construction is deliberately unable to trust caller-supplied metadata. */
    public KeyCustodyPolicy() {
        this((subject, artifactSha256, nowMs) -> KeyCustodyVerification.untrusted(
                "no trusted KMS/Vault/HSM verifier is configured"));
    }

    public KeyCustodyDecision evaluate(GovernanceSubject subject,
                                       String evidenceArtifactSha256,
                                       long nowMs) {
        List<String> blockers = new ArrayList<>();
        if (subject == null || evidenceArtifactSha256 == null
                || !evidenceArtifactSha256.matches("[0-9a-f]{64}") || nowMs <= 0) {
            return new KeyCustodyDecision(false,
                    List.of("exact key custody subject and artifact are required"));
        }
        KeyCustodyVerification verification;
        try {
            verification = verifier.verify(subject, evidenceArtifactSha256, nowMs);
        } catch (RuntimeException verifierFailure) {
            return new KeyCustodyDecision(false,
                    List.of("KMS/Vault/HSM verification failed"));
        }
        if (verification == null || !verification.trusted()) {
            return new KeyCustodyDecision(false,
                    verification == null || verification.blockers().isEmpty()
                            ? List.of("KMS/Vault/HSM attestation is NOT_VERIFIED")
                            : verification.blockers());
        }
        KeyCustodyAttestation attestation = verification.attestation();
        if (!subject.environment().equals(attestation.environment())) {
            blockers.add("key custody environment is bound to a different subject");
        }
        if (!subject.keyReference().equals(attestation.keyReference())) {
            blockers.add("key custody reference is bound to a different subject");
        }
        if (!evidenceArtifactSha256.equals(attestation.artifactSha256())) {
            blockers.add("key custody artifact hash mismatch");
        }
        if (nowMs < attestation.verifiedAtMs() || nowMs >= attestation.expiresAtMs()) {
            blockers.add("key custody attestation is not active");
        }
        if (nowMs - attestation.verifiedAtMs() > MAX_VERIFICATION_AGE_MS) {
            blockers.add("key custody verification is stale");
        }
        if (attestation.expiresAtMs() - attestation.verifiedAtMs() > MAX_ATTESTATION_TTL_MS) {
            blockers.add("key custody attestation TTL exceeds fifteen minutes");
        }
        if (!attestation.nonExportable()) blockers.add("key must be non-exportable");
        if (!attestation.signingOnly()) blockers.add("key capability must be signing-only");
        if (!attestation.withdrawalsDisabled()) blockers.add("withdrawal capability must be disabled");
        if (!attestation.sourceIpRestricted()) blockers.add("source IP restriction is required");
        if (attestation.rotationDrillAtMs() > nowMs
                || nowMs - attestation.rotationDrillAtMs() > MAX_DRILL_AGE_MS) {
            blockers.add("key rotation drill is missing or stale");
        }
        if (attestation.revocationDrillAtMs() > nowMs
                || nowMs - attestation.revocationDrillAtMs() > MAX_DRILL_AGE_MS) {
            blockers.add("key revocation drill is missing or stale");
        }
        return new KeyCustodyDecision(blockers.isEmpty(), blockers);
    }
}
