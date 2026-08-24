package com.tj.crypto.strategy.lifecycle;

import java.util.Optional;

public interface TrustedSigningKeyProvider {
    Optional<TrustedSigningKey> find(String keyId);
}
