package com.tj.crypto.strategy.lifecycle;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
class DatabaseTrustedSigningKeyProvider implements TrustedSigningKeyProvider {
    private final StrategyLifecycleMapper mapper;

    DatabaseTrustedSigningKeyProvider(StrategyLifecycleMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<TrustedSigningKey> find(String keyId) {
        TrustedSigningKeyRow row = mapper.selectSigningKey(keyId);
        if (row == null) return Optional.empty();
        return Optional.of(new TrustedSigningKey(row.getKeyId(), row.getAlgorithm(),
                row.getPublicKeyX509Base64(), row.getStatus(),
                Instant.ofEpochMilli(row.getValidFromMs()), Instant.ofEpochMilli(row.getExpiresAtMs()),
                row.getRevokedAtMs() == null ? null : Instant.ofEpochMilli(row.getRevokedAtMs())));
    }
}
