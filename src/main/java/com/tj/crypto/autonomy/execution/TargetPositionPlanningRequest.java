package com.tj.crypto.autonomy.execution;

import com.tj.crypto.autonomy.capital.Account;
import com.tj.crypto.autonomy.capital.CapitalEnvelopeResolution;
import com.tj.crypto.autonomy.capital.InstrumentKey;
import com.tj.crypto.autonomy.capital.InstrumentSpecification;
import com.tj.crypto.autonomy.capital.Quantity;
import com.tj.crypto.autonomy.capital.RiskLimits;
import com.tj.crypto.autonomy.capital.RiskScope;
import com.tj.crypto.autonomy.capital.Subaccount;
import com.tj.crypto.autonomy.capital.Venue;

import java.time.Instant;
import java.util.Objects;

/** Complete immutable input for compiling a target position into a non-dispatchable candidate. */
public final class TargetPositionPlanningRequest {
    private final String requestId;
    private final InstrumentKey instrumentKey;
    private final InstrumentSpecification instrumentSpecification;
    private final Account account;
    private final Subaccount subaccount;
    private final Venue venue;
    private final Quantity targetPosition;
    private final PositionValuationSnapshot positionValuation;
    private final CapitalEnvelopeResolution capitalEnvelope;
    private final StrategyReleaseBinding strategyRelease;
    private final ExecutionPolicyBinding policyBinding;
    private final OrderPreference orderPreference;
    private final Instant requestedAt;
    private final Instant validUntil;
    private final String sha256;

    public TargetPositionPlanningRequest(
            String requestId,
            InstrumentKey instrumentKey,
            InstrumentSpecification instrumentSpecification,
            Account account,
            Subaccount subaccount,
            Venue venue,
            Quantity targetPosition,
            PositionValuationSnapshot positionValuation,
            CapitalEnvelopeResolution capitalEnvelope,
            StrategyReleaseBinding strategyRelease,
            ExecutionPolicyBinding policyBinding,
            OrderPreference orderPreference,
            Instant requestedAt,
            Instant validUntil) {
        this.requestId = ExecutionValues.id(requestId, "requestId", 192);
        this.instrumentKey = Objects.requireNonNull(instrumentKey, "instrumentKey");
        this.instrumentSpecification = Objects.requireNonNull(
                instrumentSpecification, "instrumentSpecification");
        if (!instrumentKey.equals(instrumentSpecification.instrumentKey())) {
            throw new IllegalArgumentException("instrument specification belongs to another key");
        }
        this.account = Objects.requireNonNull(account, "account");
        this.subaccount = Objects.requireNonNull(subaccount, "subaccount");
        this.venue = Objects.requireNonNull(venue, "venue");
        if (!subaccount.parentId().equals(account.id())) {
            throw new IllegalArgumentException("subaccount does not belong to account");
        }
        if (!account.venue().equals(venue) || !subaccount.venue().equals(venue)) {
            throw new IllegalArgumentException("account topology and request venue differ");
        }
        this.targetPosition = Objects.requireNonNull(targetPosition, "targetPosition");
        ExecutionValues.decimal(targetPosition.amount(), "targetPosition");
        if (!targetPosition.unit().equals(instrumentSpecification.quantityUnit())) {
            throw new IllegalArgumentException("target position quantity unit differs from specification");
        }
        this.positionValuation = Objects.requireNonNull(
                positionValuation, "positionValuation");
        this.capitalEnvelope = Objects.requireNonNull(capitalEnvelope, "capitalEnvelope");
        this.strategyRelease = Objects.requireNonNull(strategyRelease, "strategyRelease");
        this.policyBinding = Objects.requireNonNull(policyBinding, "policyBinding");
        this.orderPreference = Objects.requireNonNull(orderPreference, "orderPreference");
        this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
        this.validUntil = Objects.requireNonNull(validUntil, "validUntil");
        if (!validUntil.isAfter(requestedAt)) {
            throw new IllegalArgumentException("request validity must end after request time");
        }
        this.sha256 = ExecutionValues.digest(canonical());
    }

    public String requestId() { return requestId; }
    public InstrumentKey instrumentKey() { return instrumentKey; }
    public InstrumentSpecification instrumentSpecification() { return instrumentSpecification; }
    public Account account() { return account; }
    public Subaccount subaccount() { return subaccount; }
    public Venue venue() { return venue; }
    public Quantity targetPosition() { return targetPosition; }
    public PositionValuationSnapshot positionValuation() { return positionValuation; }
    public CapitalEnvelopeResolution capitalEnvelope() { return capitalEnvelope; }
    public StrategyReleaseBinding strategyRelease() { return strategyRelease; }
    public ExecutionPolicyBinding policyBinding() { return policyBinding; }
    public OrderPreference orderPreference() { return orderPreference; }
    public Instant requestedAt() { return requestedAt; }
    public Instant validUntil() { return validUntil; }
    public String sha256() { return sha256; }

    private String canonical() {
        StringBuilder value = new StringBuilder("target-position-planning-request-v1");
        ExecutionValues.append(value, requestId);
        ExecutionValues.append(value, instrumentKey.canonicalId());
        ExecutionValues.append(value, instrumentSpecification.sha256());
        ExecutionValues.append(value, account.id());
        ExecutionValues.append(value, subaccount.id());
        ExecutionValues.append(value, subaccount.parentId());
        ExecutionValues.append(value, venue.id());
        ExecutionValues.append(value, targetPosition.amount().toPlainString());
        ExecutionValues.append(value, targetPosition.unit().code());
        ExecutionValues.append(value, positionValuation.sha256());
        appendEnvelope(value, capitalEnvelope);
        ExecutionValues.append(value, strategyRelease.canonicalId());
        ExecutionValues.append(value, policyBinding.canonicalId());
        ExecutionValues.append(value, orderPreference.orderType());
        ExecutionValues.append(value, orderPreference.timeInForce());
        ExecutionValues.append(value, orderPreference.limitPrice() == null
                ? null : orderPreference.limitPrice().toPlainString());
        ExecutionValues.append(value, requestedAt);
        ExecutionValues.append(value, validUntil);
        return value.toString();
    }

    private static void appendEnvelope(StringBuilder value, CapitalEnvelopeResolution envelope) {
        ExecutionValues.append(value, envelope.code());
        ExecutionValues.append(value, envelope.portfolioId());
        ExecutionValues.append(value, envelope.policyId());
        ExecutionValues.append(value, envelope.policyVersion());
        ExecutionValues.append(value, envelope.policySha256());
        RiskScope scope = envelope.scope();
        ExecutionValues.append(value, scope == null ? null : scope.canonicalId());
        ExecutionValues.append(value, envelope.evaluatedAt());
        ExecutionValues.append(value, envelope.expiresAt());
        if (envelope.allocatedCapital() != null) {
            ExecutionValues.append(value, envelope.allocatedCapital().amount().toPlainString());
            ExecutionValues.append(value, envelope.allocatedCapital().unit().code());
        } else {
            ExecutionValues.append(value, null);
            ExecutionValues.append(value, null);
        }
        appendLimits(value, envelope.riskLimits());
    }

    private static void appendLimits(StringBuilder value, RiskLimits limits) {
        if (limits == null) {
            ExecutionValues.append(value, null);
            return;
        }
        ExecutionValues.append(value, limits.maxGrossNotional().amount().toPlainString());
        ExecutionValues.append(value, limits.maxNetNotional().amount().toPlainString());
        ExecutionValues.append(value, limits.maxSingleOrderNotional().amount().toPlainString());
        ExecutionValues.append(value, limits.maxDailyLoss().amount().toPlainString());
        ExecutionValues.append(value, limits.maxLossAtStop().amount().toPlainString());
        ExecutionValues.append(value, limits.valuationUnit().code());
    }
}
