package com.tj.crypto.autonomy.capital;

import java.util.Objects;

/** Stable instrument key paired with one exact, content-addressed specification. */
public record Instrument(InstrumentKey key, InstrumentSpecification specification)
        implements Comparable<Instrument> {
    public Instrument {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(specification, "specification");
        if (!key.equals(specification.instrumentKey())) {
            throw new IllegalArgumentException("instrument specification belongs to a different key");
        }
    }

    /** Stable identity only; do not use it where economic terms must be bound. */
    public String canonicalId() {
        return key.canonicalId();
    }

    /** Exact identity used by policies and risk scopes. */
    public String boundCanonicalId() {
        return CapitalValues.canonicalId("bound-instrument-v1",
                key.canonicalId(), specification.sha256());
    }

    @Override
    public int compareTo(Instrument other) {
        return boundCanonicalId().compareTo(other.boundCanonicalId());
    }
}
