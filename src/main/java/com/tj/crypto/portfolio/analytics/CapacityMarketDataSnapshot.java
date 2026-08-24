package com.tj.crypto.portfolio.analytics;

import com.tj.crypto.common.domain.InstrumentId;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Content-addressed, point-in-time market inputs for a linear capacity estimate. */
public record CapacityMarketDataSnapshot(
        InstrumentId instrumentId,
        String quoteCurrency,
        ExposureValuationModel valuationModel,
        CapacityVolumeUnit averageDailyVolumeUnit,
        BigDecimal averageDailyVolume,
        BigDecimal contractMultiplier,
        BigDecimal referencePrice,
        long statisticsStartTimeMs,
        long statisticsEndTimeMs,
        long referencePriceTimeMs,
        long analysisAsOfTimeMs,
        long maxReferencePriceAgeMs,
        String sourceManifestSha256
) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public CapacityMarketDataSnapshot {
        Objects.requireNonNull(instrumentId, "instrumentId");
        quoteCurrency = required(quoteCurrency, "quoteCurrency").toUpperCase(Locale.ROOT);
        if (valuationModel != ExposureValuationModel.LINEAR_QUOTE_SETTLED) {
            throw new IllegalArgumentException("capacity supports only LINEAR_QUOTE_SETTLED instruments");
        }
        Objects.requireNonNull(averageDailyVolumeUnit, "averageDailyVolumeUnit");
        averageDailyVolume = positive(averageDailyVolume, "averageDailyVolume");
        contractMultiplier = positive(contractMultiplier, "contractMultiplier");
        referencePrice = positive(referencePrice, "referencePrice");
        if (averageDailyVolumeUnit != CapacityVolumeUnit.CONTRACTS
                && contractMultiplier.compareTo(BigDecimal.ONE) != 0) {
            throw new IllegalArgumentException("contractMultiplier must equal 1 unless volume unit is CONTRACTS");
        }
        if (statisticsStartTimeMs <= 0 || statisticsEndTimeMs <= statisticsStartTimeMs
                || analysisAsOfTimeMs < statisticsEndTimeMs) {
            throw new IllegalArgumentException("invalid capacity statistics window");
        }
        if (referencePriceTimeMs <= 0 || referencePriceTimeMs > analysisAsOfTimeMs) {
            throw new IllegalArgumentException("reference price cannot be future or undated");
        }
        if (maxReferencePriceAgeMs < 0
                || analysisAsOfTimeMs - referencePriceTimeMs > maxReferencePriceAgeMs) {
            throw new IllegalArgumentException("reference price is stale at analysisAsOfTimeMs");
        }
        sourceManifestSha256 = required(sourceManifestSha256, "sourceManifestSha256")
                .toLowerCase(Locale.ROOT);
        if (!SHA_256.matcher(sourceManifestSha256).matches()) {
            throw new IllegalArgumentException("sourceManifestSha256 must be 64 lowercase hex characters");
        }
    }

    public BigDecimal averageDailyVolumeNotional() {
        return switch (averageDailyVolumeUnit) {
            case QUOTE_NOTIONAL -> averageDailyVolume;
            case BASE_ASSET_QUANTITY -> averageDailyVolume.multiply(referencePrice);
            case CONTRACTS -> averageDailyVolume.multiply(contractMultiplier).multiply(referencePrice);
        };
    }

    private static BigDecimal positive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException(field + " must be positive");
        return value.stripTrailingZeros();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
