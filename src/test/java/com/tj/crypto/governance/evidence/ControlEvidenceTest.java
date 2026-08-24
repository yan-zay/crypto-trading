package com.tj.crypto.governance.evidence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlEvidenceTest {
    private static final String HASH = "a".repeat(64);
    private static final String SUBJECT = "e".repeat(64);

    @Test
    void verifiedEvidenceRequiresExternalArtifactAndExpiry() {
        assertThatThrownBy(() -> new ControlEvidence("e-1", ControlKind.MFA, "desk-a", "pro",
                SUBJECT, "policy-1", EvidenceStatus.VERIFIED, "owner", "", 1, 2, 3, 4,
                "", "", "", ""))
                .hasMessageContaining("issuer");

        ControlEvidence evidence = verified(ControlKind.MFA, 1, 2, 10);
        assertThat(evidence.environment()).isEqualTo("PRO");
        assertThat(evidence.isActiveAt(5)).isTrue();
        assertThat(evidence.isActiveAt(10)).isFalse();
    }

    @Test
    void revocationMustPointToPriorEvidence() {
        assertThatThrownBy(() -> new ControlEvidence("e-2", ControlKind.MFA, "desk-a", "pro",
                SUBJECT, "policy-1", EvidenceStatus.REVOKED, "owner", "issuer", 0, 0, 0, 4,
                "", "", "", "revoked"))
                .hasMessageContaining("predecessor");
    }

    private ControlEvidence verified(ControlKind kind, long start, long complete, long expiry) {
        return new ControlEvidence("e-" + kind, kind, "desk-a", "pro", SUBJECT, "policy-1",
                EvidenceStatus.VERIFIED, "owner", "external-auditor", start, complete,
                expiry, complete, "evidence://immutable/evidence.json", HASH, "", "reviewed");
    }
}
