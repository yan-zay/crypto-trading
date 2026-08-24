package com.tj.crypto.portfolio.analytics;

import com.tj.crypto.common.domain.InstrumentId;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable marked position used only as an input to portfolio analytics. */
public record PortfolioExposure(
        String accountId,
        String strategyId,
        InstrumentId instrumentId,
        ExposureDirection direction,
        BigDecimal quantity,
        BigDecimal markPrice,
        BigDecimal contractMultiplier,
        ExposureValuationModel valuationModel,
        String quoteCurrency,
        long markEventTimeMs,
        long markObservedAtMs,
        String sourceManifestSha256,
        SnapshotCompleteness completeness
) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public PortfolioExposure(String accountId, String strategyId, InstrumentId instrumentId,
                             ExposureDirection direction, BigDecimal quantity, BigDecimal markPrice,
                             BigDecimal contractMultiplier, ExposureValuationModel valuationModel) {
        this(accountId, strategyId, instrumentId, direction, quantity, markPrice, contractMultiplier,
                valuationModel, null, 0, 0, null, SnapshotCompleteness.INCOMPLETE);
    }

    public PortfolioExposure {
        accountId = required(accountId, "accountId");
        strategyId = required(strategyId, "strategyId");
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(direction, "direction");
        quantity = positive(quantity, "quantity");
        markPrice = positive(markPrice, "markPrice");
        contractMultiplier = positive(contractMultiplier, "contractMultiplier");
        Objects.requireNonNull(valuationModel, "valuationModel");
        if (valuationModel != ExposureValuationModel.LINEAR_QUOTE_SETTLED) {
            throw new IllegalArgumentException("only LINEAR_QUOTE_SETTLED exposure is supported");
        }
        quoteCurrency = required(quoteCurrency, "quoteCurrency").toUpperCase(Locale.ROOT);
        if (markEventTimeMs <= 0 || markObservedAtMs < markEventTimeMs) {
            throw new IllegalArgumentException("invalid mark event/observation time");
        }
        sourceManifestSha256 = required(sourceManifestSha256, "sourceManifestSha256")
                .toLowerCase(Locale.ROOT);
        if (!SHA_256.matcher(sourceManifestSha256).matches()) {
            throw new IllegalArgumentException("sourceManifestSha256 must be 64 lowercase hex characters");
        }
        Objects.requireNonNull(completeness, "completeness");
    }

    public BigDecimal grossNotional() {
        return quantity.multiply(markPrice).multiply(contractMultiplier);
    }

    public BigDecimal signedNotional() {
        return grossNotional().multiply(BigDecimal.valueOf(direction.sign()));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static BigDecimal positive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value.stripTrailingZeros();
    }
}
