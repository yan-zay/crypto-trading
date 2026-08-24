package com.tj.crypto.strategy.lifecycle;

import com.tj.crypto.research.experiment.ExperimentPromotionEvidence;
import com.tj.crypto.research.experiment.ExperimentRegistryService;
import com.tj.crypto.research.experiment.ExperimentCertificationEvidence;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/** Converts the registry's audited promotion projection into canonical lifecycle evidence. */
@Component
final class ExperimentRegistryResearchEvidenceProvider
        implements RegisteredResearchEvidenceProvider {
    private final ExperimentRegistryService registryService;

    ExperimentRegistryResearchEvidenceProvider(ExperimentRegistryService registryService) {
        this.registryService = registryService;
    }

    @Override
    public RegisteredResearchEvidence assess(SignedStrategyArtifact artifact,
                                             StrategyEvidenceType evidenceType,
                                             Instant now) {
        if (evidenceType != StrategyEvidenceType.RESEARCH
                && evidenceType != StrategyEvidenceType.REPRODUCIBILITY) {
            return RegisteredResearchEvidence.denied("evidence type is not registry-backed");
        }
        if (artifact.researchRegistrationId() == null
                || artifact.researchRegistrationSha256() == null) {
            return RegisteredResearchEvidence.denied(
                    "signed artifact is missing research registration id/SHA-256");
        }
        ExperimentPromotionEvidence evidence;
        try {
            evidence = registryService.promotionEvidence(artifact.researchRegistrationId(),
                    artifact.researchRegistrationSha256());
        } catch (RuntimeException failure) {
            return RegisteredResearchEvidence.denied("experiment registry lookup failed");
        }
        ExperimentCertificationEvidence certification = evidence.certification();
        if (!evidence.eligible() || !evidence.completeTrialFamily()
                || !evidence.allTrialsTerminal() || !evidence.allTrialsSucceeded()
                || !evidence.auditChainValid() || certification == null
                || !certification.certifiedAt(now.toEpochMilli())
                || !artifact.researchRegistrationSha256().equals(evidence.registrationSha256())) {
            List<String> blockers = evidence.reasons().isEmpty()
                    ? List.of("experiment family is incomplete, non-terminal, or has an invalid audit chain")
                    : evidence.reasons();
            return new RegisteredResearchEvidence(false, new byte[0], null, "", blockers);
        }
        String canonical = "CRYPTO_TRADING_EXPERIMENT_PROMOTION_V1\n"
                + "evidenceType=" + evidenceType.name() + "\n"
                + "registrationId=" + evidence.registrationId() + "\n"
                + "registrationSha256=" + evidence.registrationSha256() + "\n"
                + "completeTrialFamily=" + evidence.completeTrialFamily() + "\n"
                + "allTrialsTerminal=" + evidence.allTrialsTerminal() + "\n"
                + "allTrialsSucceeded=" + evidence.allTrialsSucceeded() + "\n"
                + "auditChainValid=" + evidence.auditChainValid() + "\n"
                + "certificationReportIdentitySha256=" + Hashes.sha256((certification.reportUri()
                + "\u0000" + certification.reportVersion()).getBytes(StandardCharsets.UTF_8)) + "\n"
                + "certificationReportSha256=" + certification.reportSha256() + "\n"
                + "certificationPolicySha256=" + certification.policySha256() + "\n"
                + "certificationSignerKeyIdSha256=" + Hashes.sha256(
                certification.signerKeyId().getBytes(StandardCharsets.UTF_8)) + "\n"
                + "certificationSignatureAlgorithmSha256=" + Hashes.sha256(
                certification.signatureAlgorithm().getBytes(StandardCharsets.UTF_8)) + "\n"
                + "certificationSignatureSha256=" + Hashes.sha256(
                certification.signature().getBytes(StandardCharsets.UTF_8)) + "\n"
                + "certificationVerifiedAtEpochMillis=" + certification.verifiedAtEpochMillis() + "\n"
                + "certificationExpiresAtEpochMillis=" + certification.expiresAtEpochMillis() + "\n";
        byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
        String digest = Hashes.sha256(bytes);
        String uri = "registry://experiments/" + evidence.registrationId()
                + "/promotion-evidence/" + digest;
        return new RegisteredResearchEvidence(true, bytes, digest, uri, List.of());
    }
}
