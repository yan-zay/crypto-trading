package com.tj.crypto.autonomy.execution;

import com.tj.crypto.autonomy.capital.InstrumentKey;
import com.tj.crypto.autonomy.capital.InstrumentSpecification;
import com.tj.crypto.autonomy.capital.Quantity;
import com.tj.crypto.autonomy.capital.Venue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** Exact question asked of the trusted venue capability boundary. */
public final class VenueExecutionCapabilityRequest {
    private final String planningRequestSha256;
    private final String accountId;
    private final String subaccountId;
    private final Venue venue;
    private final InstrumentKey instrumentKey;
    private final InstrumentSpecification instrumentSpecification;
    private final ExecutionSide side;
    private final Quantity absoluteDelta;
    private final BigDecimal referencePrice;
    private final OrderPreference orderPreference;
    private final Instant evaluatedAt;
    private final String sha256;

    public VenueExecutionCapabilityRequest(
            String planningRequestSha256,
            String accountId,
            String subaccountId,
            Venue venue,
            InstrumentKey instrumentKey,
            InstrumentSpecification instrumentSpecification,
            ExecutionSide side,
            Quantity absoluteDelta,
            BigDecimal referencePrice,
            OrderPreference orderPreference,
            Instant evaluatedAt) {
        this.planningRequestSha256 = ExecutionValues.sha256(
                planningRequestSha256, "planningRequestSha256");
        this.accountId = ExecutionValues.id(accountId, "accountId", 192);
        this.subaccountId = ExecutionValues.id(subaccountId, "subaccountId", 192);
        this.venue = Objects.requireNonNull(venue, "venue");
        this.instrumentKey = Objects.requireNonNull(instrumentKey, "instrumentKey");
        this.instrumentSpecification = Objects.requireNonNull(
                instrumentSpecification, "instrumentSpecification");
        if (!instrumentKey.equals(instrumentSpecification.instrumentKey())) {
            throw new IllegalArgumentException("instrument specification belongs to another key");
        }
        this.side = Objects.requireNonNull(side, "side");
        this.absoluteDelta = Objects.requireNonNull(absoluteDelta, "absoluteDelta");
        if (absoluteDelta.amount().signum() <= 0) {
            throw new IllegalArgumentException("absoluteDelta must be positive");
        }
        if (!absoluteDelta.unit().equals(instrumentSpecification.quantityUnit())) {
            throw new IllegalArgumentException("delta quantity unit differs from specification");
        }
        this.referencePrice = ExecutionValues.decimal(referencePrice, "referencePrice");
        this.orderPreference = Objects.requireNonNull(orderPreference, "orderPreference");
        this.evaluatedAt = Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        this.sha256 = ExecutionValues.digest(canonical());
    }

    public String planningRequestSha256() { return planningRequestSha256; }
    public String accountId() { return accountId; }
    public String subaccountId() { return subaccountId; }
    public Venue venue() { return venue; }
    public InstrumentKey instrumentKey() { return instrumentKey; }
    public InstrumentSpecification instrumentSpecification() { return instrumentSpecification; }
    public ExecutionSide side() { return side; }
    public Quantity absoluteDelta() { return absoluteDelta; }
    public BigDecimal referencePrice() { return referencePrice; }
    public OrderPreference orderPreference() { return orderPreference; }
    public Instant evaluatedAt() { return evaluatedAt; }
    public String sha256() { return sha256; }

    private String canonical() {
        StringBuilder value = new StringBuilder("venue-execution-capability-request-v1");
        ExecutionValues.append(value, planningRequestSha256);
        ExecutionValues.append(value, accountId);
        ExecutionValues.append(value, subaccountId);
        ExecutionValues.append(value, venue.id());
        ExecutionValues.append(value, instrumentKey.canonicalId());
        ExecutionValues.append(value, instrumentSpecification.sha256());
        ExecutionValues.append(value, side);
        ExecutionValues.append(value, absoluteDelta.amount().toPlainString());
        ExecutionValues.append(value, absoluteDelta.unit().code());
        ExecutionValues.append(value, referencePrice.toPlainString());
        ExecutionValues.append(value, orderPreference.orderType());
        ExecutionValues.append(value, orderPreference.timeInForce());
        ExecutionValues.append(value, orderPreference.limitPrice() == null
                ? null : orderPreference.limitPrice().toPlainString());
        ExecutionValues.append(value, evaluatedAt);
        return value.toString();
    }
}
