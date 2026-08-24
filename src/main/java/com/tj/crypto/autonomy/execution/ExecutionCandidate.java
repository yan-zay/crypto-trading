package com.tj.crypto.autonomy.execution;

import com.tj.crypto.autonomy.capital.InstrumentKey;
import com.tj.crypto.autonomy.capital.Money;
import com.tj.crypto.autonomy.capital.Quantity;
import com.tj.crypto.autonomy.capital.Venue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Content-addressed compilation result. It is evidence for a later OMS gate, never a permit.
 */
public final class ExecutionCandidate {
    private final String planningRequestSha256;
    private final String accountId;
    private final String subaccountId;
    private final Venue venue;
    private final InstrumentKey instrumentKey;
    private final String instrumentSpecificationSha256;
    private final String strategyReleaseBinding;
    private final String policyBinding;
    private final String positionValuationSha256;
    private final String capabilityRequestSha256;
    private final String capabilityVersion;
    private final String capabilityEvidenceSha256;
    private final ExecutionSide side;
    private final Quantity quantity;
    private final BigDecimal referencePrice;
    private final BigDecimal limitPrice;
    private final Money orderNotional;
    private final boolean reduceOnly;
    private final ExecutionOrderType orderType;
    private final ExecutionTimeInForce timeInForce;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final String sha256;

    ExecutionCandidate(
            String planningRequestSha256,
            String accountId,
            String subaccountId,
            Venue venue,
            InstrumentKey instrumentKey,
            String instrumentSpecificationSha256,
            String strategyReleaseBinding,
            String policyBinding,
            String positionValuationSha256,
            String capabilityRequestSha256,
            String capabilityVersion,
            String capabilityEvidenceSha256,
            ExecutionSide side,
            Quantity quantity,
            BigDecimal referencePrice,
            BigDecimal limitPrice,
            Money orderNotional,
            boolean reduceOnly,
            ExecutionOrderType orderType,
            ExecutionTimeInForce timeInForce,
            Instant createdAt,
            Instant expiresAt) {
        this.planningRequestSha256 = ExecutionValues.sha256(
                planningRequestSha256, "planningRequestSha256");
        this.accountId = ExecutionValues.id(accountId, "accountId", 192);
        this.subaccountId = ExecutionValues.id(subaccountId, "subaccountId", 192);
        this.venue = Objects.requireNonNull(venue, "venue");
        this.instrumentKey = Objects.requireNonNull(instrumentKey, "instrumentKey");
        this.instrumentSpecificationSha256 = ExecutionValues.sha256(
                instrumentSpecificationSha256, "instrumentSpecificationSha256");
        this.strategyReleaseBinding = ExecutionValues.id(
                strategyReleaseBinding, "strategyReleaseBinding", 768);
        this.policyBinding = ExecutionValues.id(policyBinding, "policyBinding", 768);
        this.positionValuationSha256 = ExecutionValues.sha256(
                positionValuationSha256, "positionValuationSha256");
        this.capabilityRequestSha256 = ExecutionValues.sha256(
                capabilityRequestSha256, "capabilityRequestSha256");
        this.capabilityVersion = ExecutionValues.id(
                capabilityVersion, "capabilityVersion", 96);
        this.capabilityEvidenceSha256 = ExecutionValues.sha256(
                capabilityEvidenceSha256, "capabilityEvidenceSha256");
        this.side = Objects.requireNonNull(side, "side");
        this.quantity = Objects.requireNonNull(quantity, "quantity");
        if (quantity.amount().signum() <= 0) {
            throw new IllegalArgumentException("candidate quantity must be positive");
        }
        this.referencePrice = ExecutionValues.decimal(referencePrice, "referencePrice");
        this.limitPrice = limitPrice == null ? null
                : ExecutionValues.decimal(limitPrice, "limitPrice");
        this.orderNotional = Objects.requireNonNull(orderNotional, "orderNotional");
        if (!orderNotional.isPositive()) {
            throw new IllegalArgumentException("orderNotional must be positive");
        }
        this.reduceOnly = reduceOnly;
        this.orderType = Objects.requireNonNull(orderType, "orderType");
        this.timeInForce = Objects.requireNonNull(timeInForce, "timeInForce");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("candidate must expire after creation");
        }
        this.sha256 = ExecutionValues.digest(canonical());
    }

    public String candidateId() { return "execution-candidate:sha256:" + sha256; }
    public String sha256() { return sha256; }
    public String planningRequestSha256() { return planningRequestSha256; }
    public String accountId() { return accountId; }
    public String subaccountId() { return subaccountId; }
    public Venue venue() { return venue; }
    public InstrumentKey instrumentKey() { return instrumentKey; }
    public String instrumentSpecificationSha256() { return instrumentSpecificationSha256; }
    public String strategyReleaseBinding() { return strategyReleaseBinding; }
    public String policyBinding() { return policyBinding; }
    public String positionValuationSha256() { return positionValuationSha256; }
    public String capabilityRequestSha256() { return capabilityRequestSha256; }
    public String capabilityVersion() { return capabilityVersion; }
    public String capabilityEvidenceSha256() { return capabilityEvidenceSha256; }
    public ExecutionSide side() { return side; }
    public Quantity quantity() { return quantity; }
    public BigDecimal referencePrice() { return referencePrice; }
    public BigDecimal limitPrice() { return limitPrice; }
    public Money orderNotional() { return orderNotional; }
    public boolean reduceOnly() { return reduceOnly; }
    public ExecutionOrderType orderType() { return orderType; }
    public ExecutionTimeInForce timeInForce() { return timeInForce; }
    public Instant createdAt() { return createdAt; }
    public Instant expiresAt() { return expiresAt; }

    /** Hard-coded: a candidate cannot authorize signing, reservation or dispatch. */
    public boolean dispatchAuthorized() { return false; }

    private String canonical() {
        StringBuilder value = new StringBuilder("execution-candidate-v1");
        ExecutionValues.append(value, planningRequestSha256);
        ExecutionValues.append(value, accountId);
        ExecutionValues.append(value, subaccountId);
        ExecutionValues.append(value, venue.id());
        ExecutionValues.append(value, instrumentKey.canonicalId());
        ExecutionValues.append(value, instrumentSpecificationSha256);
        ExecutionValues.append(value, strategyReleaseBinding);
        ExecutionValues.append(value, policyBinding);
        ExecutionValues.append(value, positionValuationSha256);
        ExecutionValues.append(value, capabilityRequestSha256);
        ExecutionValues.append(value, capabilityVersion);
        ExecutionValues.append(value, capabilityEvidenceSha256);
        ExecutionValues.append(value, side);
        ExecutionValues.append(value, quantity.amount().toPlainString());
        ExecutionValues.append(value, quantity.unit().code());
        ExecutionValues.append(value, referencePrice.toPlainString());
        ExecutionValues.append(value, limitPrice == null ? null : limitPrice.toPlainString());
        ExecutionValues.append(value, orderNotional.amount().toPlainString());
        ExecutionValues.append(value, orderNotional.unit().code());
        ExecutionValues.append(value, reduceOnly);
        ExecutionValues.append(value, orderType);
        ExecutionValues.append(value, timeInForce);
        ExecutionValues.append(value, createdAt);
        ExecutionValues.append(value, expiresAt);
        ExecutionValues.append(value, false);
        return value.toString();
    }
}
