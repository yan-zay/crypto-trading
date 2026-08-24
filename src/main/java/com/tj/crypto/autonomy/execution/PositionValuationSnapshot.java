package com.tj.crypto.autonomy.execution;

import com.tj.crypto.autonomy.capital.InstrumentKey;
import com.tj.crypto.autonomy.capital.Money;
import com.tj.crypto.autonomy.capital.Quantity;
import com.tj.crypto.autonomy.capital.Venue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Content-addressed point-in-time position and valuation input.
 *
 * <p>Content addressing proves identity, not truth. A durable authoritative producer remains a
 * required external integration before this snapshot can be used for dispatch.
 */
public final class PositionValuationSnapshot {
    private final String accountId;
    private final String subaccountId;
    private final Venue venue;
    private final InstrumentKey instrumentKey;
    private final String instrumentSpecificationSha256;
    private final Quantity currentPosition;
    private final BigDecimal referencePrice;
    private final Money currentGrossNotional;
    private final Money currentNetNotional;
    private final Instant observedAt;
    private final Instant expiresAt;
    private final SnapshotCompleteness completeness;
    private final String sourceManifestSha256;
    private final String sha256;

    public PositionValuationSnapshot(
            String accountId,
            String subaccountId,
            Venue venue,
            InstrumentKey instrumentKey,
            String instrumentSpecificationSha256,
            Quantity currentPosition,
            BigDecimal referencePrice,
            Money currentGrossNotional,
            Money currentNetNotional,
            Instant observedAt,
            Instant expiresAt,
            SnapshotCompleteness completeness,
            String sourceManifestSha256) {
        this.accountId = ExecutionValues.id(accountId, "accountId", 192);
        this.subaccountId = ExecutionValues.id(subaccountId, "subaccountId", 192);
        this.venue = Objects.requireNonNull(venue, "venue");
        this.instrumentKey = Objects.requireNonNull(instrumentKey, "instrumentKey");
        this.instrumentSpecificationSha256 = ExecutionValues.sha256(
                instrumentSpecificationSha256, "instrumentSpecificationSha256");
        this.currentPosition = Objects.requireNonNull(currentPosition, "currentPosition");
        ExecutionValues.decimal(currentPosition.amount(), "currentPosition");
        this.referencePrice = ExecutionValues.decimal(referencePrice, "referencePrice");
        this.currentGrossNotional = Objects.requireNonNull(
                currentGrossNotional, "currentGrossNotional");
        if (!currentGrossNotional.isNonNegative()) {
            throw new IllegalArgumentException("currentGrossNotional must not be negative");
        }
        this.currentNetNotional = Objects.requireNonNull(currentNetNotional, "currentNetNotional");
        if (!currentNetNotional.unit().equals(currentGrossNotional.unit())) {
            throw new IllegalArgumentException("gross and net notionals must use one unit");
        }
        ExecutionValues.decimal(currentGrossNotional.amount(), "currentGrossNotional");
        ExecutionValues.decimal(currentNetNotional.amount(), "currentNetNotional");
        this.observedAt = Objects.requireNonNull(observedAt, "observedAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(observedAt)) {
            throw new IllegalArgumentException("snapshot expiry must be after observation");
        }
        this.completeness = Objects.requireNonNull(completeness, "completeness");
        this.sourceManifestSha256 = ExecutionValues.sha256(
                sourceManifestSha256, "sourceManifestSha256");
        this.sha256 = ExecutionValues.digest(canonical());
    }

    public String accountId() { return accountId; }
    public String subaccountId() { return subaccountId; }
    public Venue venue() { return venue; }
    public InstrumentKey instrumentKey() { return instrumentKey; }
    public String instrumentSpecificationSha256() { return instrumentSpecificationSha256; }
    public Quantity currentPosition() { return currentPosition; }
    public BigDecimal referencePrice() { return referencePrice; }
    public Money currentGrossNotional() { return currentGrossNotional; }
    public Money currentNetNotional() { return currentNetNotional; }
    public Instant observedAt() { return observedAt; }
    public Instant expiresAt() { return expiresAt; }
    public SnapshotCompleteness completeness() { return completeness; }
    public String sourceManifestSha256() { return sourceManifestSha256; }
    public String sha256() { return sha256; }

    public boolean currentAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return !observedAt.isAfter(instant) && expiresAt.isAfter(instant);
    }

    private String canonical() {
        StringBuilder value = new StringBuilder("position-valuation-snapshot-v1");
        ExecutionValues.append(value, accountId);
        ExecutionValues.append(value, subaccountId);
        ExecutionValues.append(value, venue.id());
        ExecutionValues.append(value, instrumentKey.canonicalId());
        ExecutionValues.append(value, instrumentSpecificationSha256);
        ExecutionValues.append(value, currentPosition.amount().toPlainString());
        ExecutionValues.append(value, currentPosition.unit().code());
        ExecutionValues.append(value, referencePrice.toPlainString());
        ExecutionValues.append(value, currentGrossNotional.amount().toPlainString());
        ExecutionValues.append(value, currentGrossNotional.unit().code());
        ExecutionValues.append(value, currentNetNotional.amount().toPlainString());
        ExecutionValues.append(value, currentNetNotional.unit().code());
        ExecutionValues.append(value, observedAt);
        ExecutionValues.append(value, expiresAt);
        ExecutionValues.append(value, completeness);
        ExecutionValues.append(value, sourceManifestSha256);
        return value.toString();
    }
}
