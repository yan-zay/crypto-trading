package com.tj.crypto.autonomy.execution;

import com.tj.crypto.autonomy.capital.CapitalEnvelopeResolution;
import com.tj.crypto.autonomy.capital.Instrument;
import com.tj.crypto.autonomy.capital.Money;
import com.tj.crypto.autonomy.capital.PriceDomain;
import com.tj.crypto.autonomy.capital.Quantity;
import com.tj.crypto.autonomy.capital.RiskLimits;
import com.tj.crypto.autonomy.capital.RiskScope;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Side-effect-free target-position compiler. It has no signer, executor, OMS or network port.
 */
public final class DeterministicExecutionPlanner {
    public static final Duration DEFAULT_CANDIDATE_TTL = Duration.ofSeconds(60);
    public static final Duration MAXIMUM_CANDIDATE_TTL = Duration.ofSeconds(120);

    private final Clock clock;
    private final VenueExecutionCapabilityVerifier capabilityVerifier;
    private final Duration candidateTtl;

    /** Safe default: venue capability is absent, so no candidate can be produced. */
    public DeterministicExecutionPlanner(Clock clock) {
        this(clock, new RejectingVenueExecutionCapabilityVerifier(), DEFAULT_CANDIDATE_TTL);
    }

    public DeterministicExecutionPlanner(
            Clock clock,
            VenueExecutionCapabilityVerifier capabilityVerifier,
            Duration candidateTtl) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.capabilityVerifier = Objects.requireNonNull(
                capabilityVerifier, "capabilityVerifier");
        this.candidateTtl = Objects.requireNonNull(candidateTtl, "candidateTtl");
        if (candidateTtl.isZero() || candidateTtl.isNegative()
                || candidateTtl.compareTo(MAXIMUM_CANDIDATE_TTL) > 0) {
            throw new IllegalArgumentException("candidateTtl must be positive and at most 120 seconds");
        }
    }

    public ExecutionPlan plan(TargetPositionPlanningRequest request) {
        Objects.requireNonNull(request, "request");
        Instant now = clock.instant();
        if (request.requestedAt().isAfter(now) || !request.validUntil().isAfter(now)) {
            return denied(ExecutionPlanningCode.REQUEST_EXPIRED, request, now);
        }

        PositionValuationSnapshot snapshot = request.positionValuation();
        if (snapshot.completeness() != SnapshotCompleteness.COMPLETE) {
            return denied(ExecutionPlanningCode.SNAPSHOT_INCOMPLETE, request, now);
        }
        if (!snapshot.currentAt(now)) {
            return denied(ExecutionPlanningCode.SNAPSHOT_NOT_CURRENT, request, now);
        }

        CapitalEnvelopeResolution envelope = request.capitalEnvelope();
        if (!envelope.resolved() || envelope.expiresAt() == null
                || envelope.evaluatedAt() == null || envelope.evaluatedAt().isAfter(now)
                || !envelope.expiresAt().isAfter(now)) {
            return denied(ExecutionPlanningCode.CAPITAL_ENVELOPE_DENIED, request, now);
        }
        if (!bindingsMatch(request, snapshot, envelope)) {
            return denied(ExecutionPlanningCode.BINDING_MISMATCH, request, now);
        }

        BigDecimal current = ExecutionValues.decimal(
                snapshot.currentPosition().amount(), "currentPosition");
        BigDecimal target = ExecutionValues.decimal(
                request.targetPosition().amount(), "targetPosition");
        if (current.signum() != 0 && target.signum() != 0
                && current.signum() != target.signum()) {
            return denied(ExecutionPlanningCode.POSITION_REVERSAL_REQUIRES_SEPARATE_TARGETS,
                    request, now);
        }
        BigDecimal delta = ExecutionValues.decimal(target.subtract(current), "positionDelta");
        if (delta.signum() == 0) {
            return denied(ExecutionPlanningCode.NO_POSITION_DELTA, request, now);
        }
        if (!validPrice(snapshot.referencePrice(), request.instrumentSpecification().priceDomain())
                || request.orderPreference().limitPrice() != null
                && !validPrice(request.orderPreference().limitPrice(),
                request.instrumentSpecification().priceDomain())) {
            return denied(ExecutionPlanningCode.INVALID_PRICE_RULE, request, now);
        }

        ExecutionSide side = delta.signum() > 0 ? ExecutionSide.BUY : ExecutionSide.SELL;
        Quantity absoluteDelta = new Quantity(delta.abs(),
                request.instrumentSpecification().quantityUnit());
        VenueExecutionCapabilityRequest capabilityRequest =
                new VenueExecutionCapabilityRequest(
                        request.sha256(), request.account().id(), request.subaccount().id(),
                        request.venue(), request.instrumentKey(), request.instrumentSpecification(),
                        side, absoluteDelta, snapshot.referencePrice(),
                        request.orderPreference(), now);

        VenueExecutionCapabilityVerification verification;
        try {
            verification = capabilityVerifier.verify(capabilityRequest);
        } catch (RuntimeException unavailable) {
            return denied(ExecutionPlanningCode.CAPABILITY_UNAVAILABLE, request, now);
        }
        if (verification == null || !verification.verified()) {
            return denied(ExecutionPlanningCode.CAPABILITY_UNAVAILABLE, request, now);
        }
        ExecutionPlanningCode verificationFailure = verifyCapabilityBindings(
                request, capabilityRequest, verification, now);
        if (verificationFailure != null) {
            return denied(verificationFailure, request, now);
        }

        BigDecimal normalizedQuantity = normalizeQuantity(
                absoluteDelta.amount(), verification.quantityStep());
        if (normalizedQuantity.signum() == 0
                || normalizedQuantity.compareTo(verification.minimumQuantity()) < 0) {
            return denied(ExecutionPlanningCode.QUANTITY_BELOW_MINIMUM, request, now);
        }
        if (normalizedQuantity.compareTo(verification.normalizedQuantity()) != 0) {
            return denied(ExecutionPlanningCode.INVALID_QUANTITY_RULE, request, now);
        }

        boolean reduceOnly = isReduction(current, target);
        boolean createsOrIncreasesShort = target.signum() < 0
                && (current.signum() >= 0 || target.abs().compareTo(current.abs()) > 0);
        if (createsOrIncreasesShort && !verification.shortPositionSupported()) {
            return denied(ExecutionPlanningCode.SHORT_POSITION_NOT_SUPPORTED, request, now);
        }
        if (reduceOnly && !verification.reduceOnlySupported()) {
            return denied(ExecutionPlanningCode.REDUCE_ONLY_NOT_SUPPORTED, request, now);
        }

        BigDecimal normalizedLimitPrice = null;
        if (request.orderPreference().orderType() == ExecutionOrderType.LIMIT) {
            normalizedLimitPrice = normalizeLimitPrice(
                    request.orderPreference().limitPrice(), verification.priceTick(), side);
            if (verification.normalizedLimitPrice() == null
                    || normalizedLimitPrice.compareTo(verification.normalizedLimitPrice()) != 0
                    || !validPrice(normalizedLimitPrice,
                    request.instrumentSpecification().priceDomain())) {
                return denied(ExecutionPlanningCode.INVALID_PRICE_RULE, request, now);
            }
        } else if (verification.normalizedLimitPrice() != null) {
            return denied(ExecutionPlanningCode.INVALID_PRICE_RULE, request, now);
        }

        Money orderNotional = verification.orderNotional();
        ExecutionPlanningCode capitalFailure = verifyCapital(
                snapshot, envelope, orderNotional, side, reduceOnly);
        if (capitalFailure != null) {
            return denied(capitalFailure, request, now);
        }

        Instant expiresAt = earliest(request.validUntil(), snapshot.expiresAt(),
                envelope.expiresAt(), verification.expiresAt(), now.plus(candidateTtl));
        if (!expiresAt.isAfter(now)) {
            return denied(ExecutionPlanningCode.CANDIDATE_TTL_EXHAUSTED, request, now);
        }
        ExecutionCandidate candidate = new ExecutionCandidate(
                request.sha256(), request.account().id(), request.subaccount().id(),
                request.venue(), request.instrumentKey(),
                request.instrumentSpecification().sha256(),
                request.strategyRelease().canonicalId(), request.policyBinding().canonicalId(),
                snapshot.sha256(), capabilityRequest.sha256(), verification.capabilityVersion(),
                verification.evidenceSha256(), side,
                new Quantity(normalizedQuantity, request.instrumentSpecification().quantityUnit()),
                snapshot.referencePrice(), normalizedLimitPrice, orderNotional, reduceOnly,
                request.orderPreference().orderType(), request.orderPreference().timeInForce(),
                now, expiresAt);
        return ExecutionPlan.created(request.sha256(), now, candidate);
    }

    private boolean bindingsMatch(
            TargetPositionPlanningRequest request,
            PositionValuationSnapshot snapshot,
            CapitalEnvelopeResolution envelope) {
        if (!snapshot.accountId().equals(request.account().id())
                || !snapshot.subaccountId().equals(request.subaccount().id())
                || !snapshot.venue().equals(request.venue())
                || !snapshot.instrumentKey().equals(request.instrumentKey())
                || !snapshot.instrumentSpecificationSha256()
                .equals(request.instrumentSpecification().sha256())
                || !snapshot.currentPosition().unit()
                .equals(request.instrumentSpecification().quantityUnit())) {
            return false;
        }
        if (!request.policyBinding().capitalPolicyId().equals(envelope.policyId())
                || request.policyBinding().capitalPolicyVersion() != envelope.policyVersion()
                || !request.policyBinding().capitalPolicySha256().equals(envelope.policySha256())) {
            return false;
        }
        RiskScope scope = envelope.scope();
        Instrument scopedInstrument = scope == null ? null : scope.instrument();
        if (scope == null || scopedInstrument == null
                || !scope.accountId().equals(request.subaccount().id())
                || !scope.venue().equals(request.venue())
                || !scope.strategyId().equals(request.strategyRelease().strategyId())
                || !scopedInstrument.key().equals(request.instrumentKey())
                || !scopedInstrument.specification().sha256()
                .equals(request.instrumentSpecification().sha256())) {
            return false;
        }
        if (envelope.riskLimits() == null || envelope.allocatedCapital() == null) {
            return false;
        }
        return snapshot.currentGrossNotional().unit().equals(
                envelope.riskLimits().valuationUnit())
                && snapshot.currentNetNotional().unit().equals(
                envelope.riskLimits().valuationUnit())
                && envelope.allocatedCapital().unit().equals(
                envelope.riskLimits().valuationUnit());
    }

    private ExecutionPlanningCode verifyCapabilityBindings(
            TargetPositionPlanningRequest request,
            VenueExecutionCapabilityRequest capabilityRequest,
            VenueExecutionCapabilityVerification verification,
            Instant now) {
        if (verification.verifiedAt() == null || verification.expiresAt() == null
                || verification.verifiedAt().isAfter(now)
                || !verification.expiresAt().isAfter(now)) {
            return ExecutionPlanningCode.CAPABILITY_UNAVAILABLE;
        }
        if (!capabilityRequest.sha256().equals(verification.capabilityRequestSha256())
                || !request.venue().equals(verification.venue())
                || !request.instrumentKey().equals(verification.instrumentKey())
                || !request.instrumentSpecification().sha256()
                .equals(verification.instrumentSpecificationSha256())) {
            return ExecutionPlanningCode.CAPABILITY_MISMATCH;
        }
        if (verification.orderType() != request.orderPreference().orderType()) {
            return ExecutionPlanningCode.UNSUPPORTED_ORDER_TYPE;
        }
        if (verification.timeInForce() != request.orderPreference().timeInForce()) {
            return ExecutionPlanningCode.UNSUPPORTED_TIME_IN_FORCE;
        }
        if (!request.instrumentSpecification().quantityUnit().equals(verification.quantityUnit())
                || verification.priceDomain() != request.instrumentSpecification().priceDomain()) {
            return ExecutionPlanningCode.CAPABILITY_MISMATCH;
        }
        if (verification.tradingSessionStatus() != TradingSessionStatus.OPEN) {
            return ExecutionPlanningCode.TRADING_SESSION_NOT_OPEN;
        }
        return null;
    }

    private ExecutionPlanningCode verifyCapital(
            PositionValuationSnapshot snapshot,
            CapitalEnvelopeResolution envelope,
            Money orderNotional,
            ExecutionSide side,
            boolean reduceOnly) {
        RiskLimits limits = envelope.riskLimits();
        try {
            ExecutionValues.positive(orderNotional.amount(), "orderNotional");
            if (!orderNotional.unit().equals(limits.valuationUnit())) {
                return ExecutionPlanningCode.VALUATION_MISMATCH;
            }
            if (orderNotional.compareTo(limits.maxSingleOrderNotional()) > 0
                    || orderNotional.compareTo(envelope.allocatedCapital()) > 0) {
                return ExecutionPlanningCode.SINGLE_ORDER_CAPITAL_EXCEEDED;
            }
            if (!reduceOnly && snapshot.currentGrossNotional().add(orderNotional)
                    .compareTo(limits.maxGrossNotional()) > 0) {
                return ExecutionPlanningCode.GROSS_CAPITAL_EXCEEDED;
            }
            BigDecimal signedOrder = side == ExecutionSide.BUY
                    ? orderNotional.amount() : orderNotional.amount().negate();
            BigDecimal predictedNet = snapshot.currentNetNotional().amount().add(signedOrder);
            if (predictedNet.abs().compareTo(limits.maxNetNotional().amount()) > 0) {
                return ExecutionPlanningCode.NET_CAPITAL_EXCEEDED;
            }
        } catch (IllegalArgumentException mismatch) {
            return ExecutionPlanningCode.VALUATION_MISMATCH;
        }
        return null;
    }

    private static boolean validPrice(BigDecimal price, PriceDomain domain) {
        return domain == PriceDomain.SIGNED_ALLOWED || price.signum() > 0;
    }

    private static boolean isReduction(BigDecimal current, BigDecimal target) {
        return current.signum() != 0
                && (target.signum() == 0
                || current.signum() == target.signum()
                && target.abs().compareTo(current.abs()) < 0);
    }

    private static BigDecimal normalizeQuantity(BigDecimal quantity, BigDecimal step) {
        BigDecimal normalizedStep = ExecutionValues.positive(step, "quantityStep");
        return ExecutionValues.decimal(quantity.divide(normalizedStep, 0, RoundingMode.DOWN)
                .multiply(normalizedStep), "normalizedQuantity");
    }

    private static BigDecimal normalizeLimitPrice(
            BigDecimal price, BigDecimal tick, ExecutionSide side) {
        BigDecimal normalizedTick = ExecutionValues.positive(tick, "priceTick");
        RoundingMode mode = side == ExecutionSide.BUY
                ? RoundingMode.FLOOR : RoundingMode.CEILING;
        return ExecutionValues.decimal(price.divide(normalizedTick, 0, mode)
                .multiply(normalizedTick), "normalizedLimitPrice");
    }

    private static Instant earliest(Instant first, Instant... others) {
        Instant result = first;
        for (Instant candidate : others) {
            if (candidate.isBefore(result)) {
                result = candidate;
            }
        }
        return result;
    }

    private static ExecutionPlan denied(
            ExecutionPlanningCode code, TargetPositionPlanningRequest request, Instant now) {
        return ExecutionPlan.denied(code, request.sha256(), now);
    }
}
