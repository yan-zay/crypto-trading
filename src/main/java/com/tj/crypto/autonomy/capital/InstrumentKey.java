package com.tj.crypto.autonomy.capital;

import java.util.Objects;

/** Stable instrument identity that remains unchanged when economic terms are versioned. */
public record InstrumentKey(
        AssetClass assetClass,
        String namespace,
        String identifier
) implements Comparable<InstrumentKey> {
    public InstrumentKey {
        Objects.requireNonNull(assetClass, "assetClass");
        namespace = CapitalValues.code(namespace, "instrumentNamespace", 64);
        identifier = CapitalValues.id(identifier, "instrumentIdentifier", 192);
    }

    public String canonicalId() {
        return CapitalValues.canonicalId("instrument-key-v1", assetClass.code(), namespace, identifier);
    }

    @Override
    public int compareTo(InstrumentKey other) {
        return canonicalId().compareTo(other.canonicalId());
    }
}
