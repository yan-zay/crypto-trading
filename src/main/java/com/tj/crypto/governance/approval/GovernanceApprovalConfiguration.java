package com.tj.crypto.governance.approval;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GovernanceApprovalConfiguration {
    @Bean
    @ConditionalOnMissingBean(MfaAttestationVerifier.class)
    MfaAttestationVerifier unconfiguredMfaAttestationVerifier() {
        return (attestation, request, nowMs) -> MfaAssertionVerification.untrusted(
                "no trusted IdP/MFA verifier is configured");
    }

    @Bean
    @ConditionalOnMissingBean(ApprovalConsumptionStore.class)
    ApprovalConsumptionStore unconfiguredApprovalConsumptionStore() {
        return consumption -> false;
    }
}
