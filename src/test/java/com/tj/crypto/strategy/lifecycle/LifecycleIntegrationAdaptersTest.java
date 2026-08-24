package com.tj.crypto.strategy.lifecycle;

import com.tj.crypto.governance.evidence.ControlKind;
import com.tj.crypto.governance.evidence.GovernanceSubject;
import com.tj.crypto.governance.evidence.MediumTermReadinessGate;
import com.tj.crypto.governance.evidence.ReadinessDecision;
import com.tj.crypto.research.experiment.ExperimentPromotionEvidence;
import com.tj.crypto.research.experiment.ExperimentRegistryService;
import com.tj.crypto.research.experiment.ExperimentCertificationEvidence;
import com.tj.crypto.research.experiment.ExperimentCertificationVerdict;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LifecycleIntegrationAdaptersTest {
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    @Test
    void governanceAdapterPassesTheCompleteSubjectToTheStrongGate() {
        GovernanceSubject subject = subject("a".repeat(64));
        MediumTermReadinessGate gate = mock(MediumTermReadinessGate.class);
        when(gate.evaluate(subject, NOW.toEpochMilli())).thenReturn(new ReadinessDecision(true,
                List.of(), Map.of(ControlKind.LEGAL_APPROVAL, "evidence-1")));
        GovernanceExternalPromotionReadinessProvider adapter =
                new GovernanceExternalPromotionReadinessProvider(gate);

        ExternalPromotionReadiness result = adapter.assess(subject, NOW);

        assertThat(result.currentAt(NOW)).isTrue();
        assertThat(result.evidenceSetSha256()).hasSize(64);
        verify(gate).evaluate(subject, NOW.toEpochMilli());
    }

    @Test
    void experimentAdapterRequiresExactSignedRegistrationAndAuditedTerminalFamily() {
        ExperimentRegistryService registry = mock(ExperimentRegistryService.class);
        when(registry.promotionEvidence("exp-1", "b".repeat(64))).thenReturn(
                certifiedPromotion("3".repeat(64), "signature-a"));
        ExperimentRegistryResearchEvidenceProvider adapter =
                new ExperimentRegistryResearchEvidenceProvider(registry);
        SignedStrategyArtifact artifact = artifact("a".repeat(64), "exp-1", "b".repeat(64));

        RegisteredResearchEvidence evidence = adapter.assess(artifact,
                StrategyEvidenceType.RESEARCH, NOW);

        assertThat(evidence.ready()).isTrue();
        assertThat(evidence.evidenceSha256()).isEqualTo(Hashes.sha256(evidence.canonicalContent()));
        assertThat(new String(evidence.canonicalContent(), StandardCharsets.UTF_8))
                .contains("registrationId=exp-1", "allTrialsSucceeded=true",
                        "auditChainValid=true", "certificationReportSha256=" + "3".repeat(64),
                        "certificationSignatureSha256=");

        when(registry.promotionEvidence("exp-1", "b".repeat(64))).thenReturn(
                certifiedPromotion("5".repeat(64), "signature-b"));
        RegisteredResearchEvidence replacement = adapter.assess(artifact,
                StrategyEvidenceType.RESEARCH, NOW);
        assertThat(replacement.evidenceSha256()).isNotEqualTo(evidence.evidenceSha256());

        // A faulty adapter projection cannot make unsigned/NOT_VERIFIED evidence ready merely
        // by setting the legacy eligible flag.
        when(registry.promotionEvidence("exp-1", "b".repeat(64))).thenReturn(
                new ExperimentPromotionEvidence("exp-1", "b".repeat(64),
                        true, true, true, true, true, List.of()));
        assertThat(adapter.assess(artifact, StrategyEvidenceType.RESEARCH, NOW).ready()).isFalse();

        when(registry.promotionEvidence("exp-1", "b".repeat(64))).thenReturn(
                new ExperimentPromotionEvidence("exp-1", "b".repeat(64),
                        false, false, false, false, false, List.of("invalid audit chain")));
        assertThat(adapter.assess(artifact, StrategyEvidenceType.REPRODUCIBILITY, NOW).ready())
                .isFalse();
    }

    private ExperimentPromotionEvidence certifiedPromotion(String reportSha256, String signature) {
        ExperimentCertificationEvidence certification = new ExperimentCertificationEvidence(
                "exp-1", "b".repeat(64), ExperimentCertificationVerdict.PASS,
                "immutable://certification/exp-1", "v1", reportSha256, "4".repeat(64),
                "research-certifier-1", "Ed25519", signature, true,
                true, true, true, true, true,
                NOW.minusSeconds(60).toEpochMilli(), NOW.plusSeconds(3_600).toEpochMilli(), List.of());
        return new ExperimentPromotionEvidence("exp-1", "b".repeat(64),
                true, true, true, true, certification, true, List.of());
    }

    private GovernanceSubject subject(String artifactSha256) {
        return new GovernanceSubject("strategy:golden", "TEST", "paper-account",
                "a".repeat(40), "1".repeat(64), "sha256:" + "2".repeat(64),
                "3".repeat(64), "4".repeat(64), artifactSha256,
                "kms://test/key", "run-1", "policy-v1");
    }

    private SignedStrategyArtifact artifact(String digest, String registrationId,
                                            String registrationSha256) {
        return new SignedStrategyArtifact("golden", "v1", "payload".getBytes(StandardCharsets.UTF_8),
                digest, "application/json", "artifact://golden/v1", "Ed25519", "key-1",
                java.util.Base64.getEncoder().encodeToString(new byte[64]), NOW.minusSeconds(1),
                NOW.plusSeconds(3600), "author", NOW, registrationId, registrationSha256);
    }
}
