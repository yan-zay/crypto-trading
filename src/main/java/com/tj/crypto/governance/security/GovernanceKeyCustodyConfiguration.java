package com.tj.crypto.governance.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GovernanceKeyCustodyConfiguration {
    @Bean
    @ConditionalOnMissingBean(KeyCustodyVerifier.class)
    KeyCustodyVerifier unconfiguredKeyCustodyVerifier() {
        return (subject, artifactSha256, nowMs) -> KeyCustodyVerification.untrusted(
                "no trusted KMS/Vault/HSM verifier is configured");
    }
}
