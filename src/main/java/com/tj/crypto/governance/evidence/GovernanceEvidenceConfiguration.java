package com.tj.crypto.governance.evidence;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Default external trust adapter deliberately keeps every external control NOT_VERIFIED. */
@Configuration
public class GovernanceEvidenceConfiguration {
    @Bean
    @ConditionalOnMissingBean(ExternalEvidenceVerifier.class)
    ExternalEvidenceVerifier unconfiguredExternalEvidenceVerifier() {
        return (evidence, subject, nowMs) -> EvidenceArtifactVerification.untrusted(
                "no trusted external evidence verifier is configured");
    }
}
