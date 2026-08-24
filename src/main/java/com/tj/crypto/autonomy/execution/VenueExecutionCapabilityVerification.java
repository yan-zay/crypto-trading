package com.tj.crypto.autonomy.execution;

import com.tj.crypto.autonomy.capital.InstrumentKey;
import com.tj.crypto.autonomy.capital.Money;
import com.tj.crypto.autonomy.capital.PriceDomain;
import com.tj.crypto.autonomy.capital.QuantityUnit;
import com.tj.crypto.autonomy.capital.Venue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Trusted adapter receipt for exact venue terms and deterministic normalization.
 *
 * <p>The planner rechecks every binding and recalculates normalization; this receipt is never an
 * execution permit.
 */
public final class VenueExecutionCapabilityVerification {
    private final CapabilityVerificationStatus status;
    private final String reasonCode;
    private final String capabilityRequestSha256;
    private final String capabilityVersion;
    private final Venue venue;
    private final InstrumentKey instrumentKey;
    private final String instrumentSpecificationSha256;
    private final ExecutionOrderType orderType;
    private final ExecutionTimeInForce timeInForce;
    private final QuantityUnit quantityUnit;
    private final BigDecimal quantityStep;
    private final BigDecimal minimumQuantity;
    private final BigDecimal normalizedQuantity;
    private final boolean shortPositionSupported;
    private final boolean reduceOnlySupported;
    private final PriceDomain priceDomain;
    private final BigDecimal priceTick;
    private final BigDecimal normalizedLimitPrice;
    private final Money orderNotional;
    private final TradingSessionStatus tradingSessionStatus;
    private final Instant verifiedAt;
    private final Instant expiresAt;
    private final String evidenceSha256;

    private VenueExecutionCapabilityVerification(
            CapabilityVerificationStatus status,
            String reasonCode,
            String capabilityRequestSha256,
            String capabilityVersion,
            Venue venue,
            InstrumentKey instrumentKey,
            String instrumentSpecificationSha256,
            ExecutionOrderType orderType,
            ExecutionTimeInForce timeInForce,
            QuantityUnit quantityUnit,
            BigDecimal quantityStep,
            BigDecimal minimumQuantity,
            BigDecimal normalizedQuantity,
            boolean shortPositionSupported,
            boolean reduceOnlySupported,
            PriceDomain priceDomain,
            BigDecimal priceTick,
            BigDecimal normalizedLimitPrice,
            Money orderNotional,
            TradingSessionStatus tradingSessionStatus,
            Instant verifiedAt,
            Instant expiresAt,
            String evidenceSha256) {
        this.status = Objects.requireNonNull(status, "status");
        this.reasonCode = ExecutionValues.id(reasonCode, "reasonCode", 96);
        this.capabilityRequestSha256 = capabilityRequestSha256;
        this.capabilityVersion = capabilityVersion;
        this.venue = venue;
        this.instrumentKey = instrumentKey;
        this.instrumentSpecificationSha256 = instrumentSpecificationSha256;
        this.orderType = orderType;
        this.timeInForce = timeInForce;
        this.quantityUnit = quantityUnit;
        this.quantityStep = quantityStep;
        this.minimumQuantity = minimumQuantity;
        this.normalizedQuantity = normalizedQuantity;
        this.shortPositionSupported = shortPositionSupported;
        this.reduceOnlySupported = reduceOnlySupported;
        this.priceDomain = priceDomain;
        this.priceTick = priceTick;
        this.normalizedLimitPrice = normalizedLimitPrice;
        this.orderNotional = orderNotional;
        this.tradingSessionStatus = tradingSessionStatus;
        this.verifiedAt = verifiedAt;
        this.expiresAt = expiresAt;
        this.evidenceSha256 = evidenceSha256;
    }

    public static VenueExecutionCapabilityVerification notWired() {
        return rejected(CapabilityVerificationStatus.NOT_WIRED, "VENUE_CAPABILITY_NOT_WIRED");
    }

    public static VenueExecutionCapabilityVerification rejected(String reasonCode) {
        return rejected(CapabilityVerificationStatus.REJECTED, reasonCode);
    }

    private static VenueExecutionCapabilityVerification rejected(
            CapabilityVerificationStatus status, String reasonCode) {
        return new VenueExecutionCapabilityVerification(status, reasonCode,
                null, null, null, null, null,
                null, null, null,
                null, null, null,
                false, false,
                null, null, null, null, null,
                null, null, null);
    }

    public static VenueExecutionCapabilityVerification verified(
            String capabilityRequestSha256,
            String capabilityVersion,
            Venue venue,
            InstrumentKey instrumentKey,
            String instrumentSpecificationSha256,
            ExecutionOrderType orderType,
            ExecutionTimeInForce timeInForce,
            QuantityUnit quantityUnit,
            BigDecimal quantityStep,
            BigDecimal minimumQuantity,
            BigDecimal normalizedQuantity,
            boolean shortPositionSupported,
            boolean reduceOnlySupported,
            PriceDomain priceDomain,
            BigDecimal priceTick,
            BigDecimal normalizedLimitPrice,
            Money orderNotional,
            TradingSessionStatus tradingSessionStatus,
            Instant verifiedAt,
            Instant expiresAt,
            String evidenceSha256) {
        String requestDigest = ExecutionValues.sha256(
                capabilityRequestSha256, "capabilityRequestSha256");
        String version = ExecutionValues.id(capabilityVersion, "capabilityVersion", 96);
        Objects.requireNonNull(venue, "venue");
        Objects.requireNonNull(instrumentKey, "instrumentKey");
        String specificationDigest = ExecutionValues.sha256(
                instrumentSpecificationSha256, "instrumentSpecificationSha256");
        Objects.requireNonNull(orderType, "orderType");
        Objects.requireNonNull(timeInForce, "timeInForce");
        Objects.requireNonNull(quantityUnit, "quantityUnit");
        BigDecimal step = ExecutionValues.positive(quantityStep, "quantityStep");
        BigDecimal minimum = ExecutionValues.positive(minimumQuantity, "minimumQuantity");
        BigDecimal normalized = ExecutionValues.positive(
                normalizedQuantity, "normalizedQuantity");
        Objects.requireNonNull(priceDomain, "priceDomain");
        BigDecimal tick = ExecutionValues.positive(priceTick, "priceTick");
        BigDecimal price = normalizedLimitPrice == null ? null
                : ExecutionValues.decimal(normalizedLimitPrice, "normalizedLimitPrice");
        Objects.requireNonNull(orderNotional, "orderNotional");
        if (!orderNotional.isPositive()) {
            throw new IllegalArgumentException("orderNotional must be positive");
        }
        Objects.requireNonNull(tradingSessionStatus, "tradingSessionStatus");
        Objects.requireNonNull(verifiedAt, "verifiedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(verifiedAt)) {
            throw new IllegalArgumentException("capability verification must expire after verification");
        }
        String evidenceDigest = ExecutionValues.sha256(evidenceSha256, "evidenceSha256");
        return new VenueExecutionCapabilityVerification(CapabilityVerificationStatus.VERIFIED,
                "VERIFIED", requestDigest, version, venue, instrumentKey, specificationDigest,
                orderType, timeInForce, quantityUnit, step, minimum, normalized,
                shortPositionSupported, reduceOnlySupported, priceDomain, tick, price, orderNotional,
                tradingSessionStatus, verifiedAt, expiresAt,
                evidenceDigest);
    }

    public CapabilityVerificationStatus status() { return status; }
    public String reasonCode() { return reasonCode; }
    public String capabilityRequestSha256() { return capabilityRequestSha256; }
    public String capabilityVersion() { return capabilityVersion; }
    public Venue venue() { return venue; }
    public InstrumentKey instrumentKey() { return instrumentKey; }
    public String instrumentSpecificationSha256() { return instrumentSpecificationSha256; }
    public ExecutionOrderType orderType() { return orderType; }
    public ExecutionTimeInForce timeInForce() { return timeInForce; }
    public QuantityUnit quantityUnit() { return quantityUnit; }
    public BigDecimal quantityStep() { return quantityStep; }
    public BigDecimal minimumQuantity() { return minimumQuantity; }
    public BigDecimal normalizedQuantity() { return normalizedQuantity; }
    public boolean shortPositionSupported() { return shortPositionSupported; }
    public boolean reduceOnlySupported() { return reduceOnlySupported; }
    public PriceDomain priceDomain() { return priceDomain; }
    public BigDecimal priceTick() { return priceTick; }
    public BigDecimal normalizedLimitPrice() { return normalizedLimitPrice; }
    public Money orderNotional() { return orderNotional; }
    public TradingSessionStatus tradingSessionStatus() { return tradingSessionStatus; }
    public Instant verifiedAt() { return verifiedAt; }
    public Instant expiresAt() { return expiresAt; }
    public String evidenceSha256() { return evidenceSha256; }

    public boolean verified() {
        return status == CapabilityVerificationStatus.VERIFIED;
    }
}
