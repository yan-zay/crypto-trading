package com.tj.crypto.autonomy.capital;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Versioned, content-addressed economic terms for one stable instrument key. */
public final class InstrumentSpecification {
    private final InstrumentKey instrumentKey;
    private final QuantityUnit quantityUnit;
    private final PriceDomain priceDomain;
    private final InstrumentValuation valuation;
    private final String specificationVersion;
    private final String sha256;

    public InstrumentSpecification(InstrumentKey instrumentKey, QuantityUnit quantityUnit,
                                   PriceDomain priceDomain, InstrumentValuation valuation,
                                   String specificationVersion) {
        this.instrumentKey = Objects.requireNonNull(instrumentKey, "instrumentKey");
        this.quantityUnit = Objects.requireNonNull(quantityUnit, "quantityUnit");
        this.priceDomain = Objects.requireNonNull(priceDomain, "priceDomain");
        this.valuation = Objects.requireNonNull(valuation, "valuation");
        this.specificationVersion = CapitalValues.id(
                specificationVersion, "specificationVersion", 96);
        this.sha256 = digest(canonicalBytes());
    }

    public InstrumentKey instrumentKey() { return instrumentKey; }
    public QuantityUnit quantityUnit() { return quantityUnit; }
    public PriceDomain priceDomain() { return priceDomain; }
    public InstrumentValuation valuation() { return valuation; }
    public String specificationVersion() { return specificationVersion; }
    public String sha256() { return sha256; }

    public byte[] canonicalBytes() {
        StringBuilder value = new StringBuilder("instrument-specification-v1");
        append(value, instrumentKey.assetClass().code());
        append(value, instrumentKey.namespace());
        append(value, instrumentKey.identifier());
        append(value, quantityUnit.code());
        append(value, priceDomain.name());
        append(value, valuation.convention());
        append(value, valuation.priceQuoteUnit().code());
        append(value, valuation.settlementUnit().code());
        append(value, valuation.contractMultiplier().toPlainString());
        append(value, valuation.termsVersion());
        append(value, specificationVersion);
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof InstrumentSpecification specification
                && sha256.equals(specification.sha256);
    }

    @Override
    public int hashCode() {
        return sha256.hashCode();
    }

    @Override
    public String toString() {
        return instrumentKey.canonicalId() + "@" + specificationVersion + "#" + sha256;
    }

    private static void append(StringBuilder target, String component) {
        target.append('|').append(component.length()).append(':').append(component);
    }

    private static String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
