package com.tj.crypto.governance.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import com.tj.crypto.governance.evidence.GovernanceSubject;

import static org.assertj.core.api.Assertions.assertThat;

class KeyCustodyPolicyTest {
    private static final long NOW = Duration.ofDays(200).toMillis();
    private static final String HASH = "d".repeat(64);
    private static final GovernanceSubject SUBJECT = new GovernanceSubject(
            "desk-a", "PRO", "account-a", "0".repeat(40), "1".repeat(64),
            "sha256:" + "2".repeat(64), "3".repeat(64), "4".repeat(64),
            "5".repeat(64), "hsm://cluster-a/signing-key-1", "run-a", "policy-a");

    @Test
    void absentAttestationIsExplicitlyNotVerified() {
        KeyCustodyDecision decision = new KeyCustodyPolicy().evaluate(SUBJECT, HASH, NOW);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.blockers()).containsExactly(
                "no trusted KMS/Vault/HSM verifier is configured");
    }

    @Test
    void requiresNonExportableLeastPrivilegeAndRecentDrills() {
        KeyCustodyAttestation attestation = new KeyCustodyAttestation(
                CustodyProvider.HASHICORP_VAULT, "vault://trading/signer/key-1", "pro",
                false, true, true, false,
                NOW - Duration.ofDays(10).toMillis(),
                NOW - Duration.ofDays(100).toMillis(),
                NOW - Duration.ofDays(2).toMillis(),
                NOW + Duration.ofDays(10).toMillis(), HASH);

        KeyCustodyDecision decision = trustedPolicy(attestation).evaluate(SUBJECT, HASH, NOW);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.blockers()).contains("key must be non-exportable",
                "source IP restriction is required", "key rotation drill is missing or stale");
    }

    @Test
    void validExternalCustodyMetadataPassesPolicy() {
        KeyCustodyAttestation attestation = new KeyCustodyAttestation(
                CustodyProvider.HSM, "hsm://cluster-a/signing-key-1", "pro",
                true, true, true, true,
                NOW - Duration.ofMinutes(1).toMillis(),
                NOW - Duration.ofDays(5).toMillis(),
                NOW - Duration.ofDays(2).toMillis(),
                NOW + Duration.ofMinutes(10).toMillis(), HASH);

        assertThat(trustedPolicy(attestation).evaluate(SUBJECT, HASH, NOW).approved()).isTrue();
    }

    private KeyCustodyPolicy trustedPolicy(KeyCustodyAttestation attestation) {
        return new KeyCustodyPolicy((subject, artifactSha256, nowMs) ->
                new KeyCustodyVerification(true, "test-kms-verifier", attestation,
                        java.util.List.of()));
    }
}
