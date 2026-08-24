package com.tj.crypto.autonomy.capital;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Versioned economic terms required before a quantity can be valued.
 *
 * <p>The convention is extensible (for example LINEAR_UNIT_PRICE or INVERSE_CONTRACT). This
 * policy kernel binds the terms but deliberately does not implement valuation arithmetic.
 */
public record InstrumentValuation(
        String convention,
        AssetUnit priceQuoteUnit,
        AssetUnit settlementUnit,
        BigDecimal contractMultiplier,
        String termsVersion
) {
    public InstrumentValuation {
        convention = CapitalValues.code(convention, "valuationConvention", 64);
        Objects.requireNonNull(priceQuoteUnit, "priceQuoteUnit");
        Objects.requireNonNull(settlementUnit, "settlementUnit");
        contractMultiplier = CapitalValues.positive(contractMultiplier, "contractMultiplier");
        termsVersion = CapitalValues.id(termsVersion, "termsVersion", 96);
    }

    public String canonicalId() {
        return CapitalValues.canonicalId("instrument-valuation-v1", convention,
                priceQuoteUnit.code(), settlementUnit.code(), contractMultiplier.toPlainString(),
                termsVersion);
    }
}
