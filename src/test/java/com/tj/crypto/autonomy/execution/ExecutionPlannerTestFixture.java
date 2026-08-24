package com.tj.crypto.autonomy.execution;

import com.tj.crypto.autonomy.capital.Account;
import com.tj.crypto.autonomy.capital.AccountCapitalAllocation;
import com.tj.crypto.autonomy.capital.AssetClass;
import com.tj.crypto.autonomy.capital.AssetUnit;
import com.tj.crypto.autonomy.capital.CapitalEnvelopeResolution;
import com.tj.crypto.autonomy.capital.CapitalPolicyKernel;
import com.tj.crypto.autonomy.capital.CapitalPolicyTrustRoot;
import com.tj.crypto.autonomy.capital.CapitalResolutionRequest;
import com.tj.crypto.autonomy.capital.CapitalRiskPolicy;
import com.tj.crypto.autonomy.capital.Instrument;
import com.tj.crypto.autonomy.capital.InstrumentKey;
import com.tj.crypto.autonomy.capital.InstrumentSpecification;
import com.tj.crypto.autonomy.capital.InstrumentValuation;
import com.tj.crypto.autonomy.capital.Money;
import com.tj.crypto.autonomy.capital.PolicyRevisionKind;
import com.tj.crypto.autonomy.capital.PriceDomain;
import com.tj.crypto.autonomy.capital.Quantity;
import com.tj.crypto.autonomy.capital.QuantityUnit;
import com.tj.crypto.autonomy.capital.RiskLimits;
import com.tj.crypto.autonomy.capital.RiskScope;
import com.tj.crypto.autonomy.capital.ScopedRiskBudget;
import com.tj.crypto.autonomy.capital.SignedCapitalRiskPolicy;
import com.tj.crypto.autonomy.capital.Subaccount;
import com.tj.crypto.autonomy.capital.TrustedPolicyKey;
import com.tj.crypto.autonomy.capital.Venue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

final class ExecutionPlannerTestFixture {
    static final Instant NOW = Instant.parse("2026-08-24T08:00:00Z");
    static final String HASH_A = "a".repeat(64);
    static final String HASH_B = "b".repeat(64);
    static final String HASH_C = "c".repeat(64);
    static final String HASH_D = "d".repeat(64);

    final AssetUnit usd = new AssetUnit("USD");
    final QuantityUnit share = new QuantityUnit("SHARE");
    final Venue venue = new Venue("regulated-broker-primary");
    final Account account = new Account("master-alpha", venue);
    final Subaccount subaccount = new Subaccount("sub-systematic", account.id(), venue);
    final InstrumentKey instrumentKey = new InstrumentKey(
            new AssetClass("EQUITY"), "FIGI", "BBG000B9XRY4");
    final InstrumentSpecification specification = new InstrumentSpecification(
            instrumentKey, share, PriceDomain.POSITIVE_ONLY,
            new InstrumentValuation("LINEAR_UNIT_PRICE", usd, usd,
                    BigDecimal.ONE, "terms-2026-08"), "specification-7");
    final Instrument instrument = new Instrument(instrumentKey, specification);
    final RiskScope scope = new RiskScope(
            subaccount.id(), venue, instrument, "strategy-global-quality");
    final StrategyReleaseBinding release = new StrategyReleaseBinding(
            scope.strategyId(), "release-42", HASH_A, HASH_B);
    final CapitalRiskPolicy policy;
    final CapitalEnvelopeResolution envelope;

    ExecutionPlannerTestFixture() {
        try {
            KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            policy = policy();
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(keys.getPrivate());
            signer.update(policy.canonicalBytes());
            SignedCapitalRiskPolicy signed = new SignedCapitalRiskPolicy(
                    policy, "capital-root", signer.sign());
            CapitalPolicyTrustRoot trustRoot = new CapitalPolicyTrustRoot(List.of(
                    new TrustedPolicyKey("capital-root", keys.getPublic(),
                            NOW.minusSeconds(86400), NOW.plusSeconds(86400))));
            CapitalPolicyKernel kernel = new CapitalPolicyKernel(
                    Clock.fixed(NOW, ZoneOffset.UTC), trustRoot,
                    (portfolioId, policyId) -> Optional.of(signed));
            envelope = kernel.resolve(new CapitalResolutionRequest(
                    policy.portfolioId(), policy.policyId(), policy.version(), policy.sha256(),
                    scope, money("1000")));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    CapitalRiskPolicy policy() {
        RiskLimits limits = new RiskLimits(
                money("2000"), money("2000"), money("500"), money("100"), money("50"));
        return new CapitalRiskPolicy(
                "capital-policy-global", 7, "portfolio-alpha", PolicyRevisionKind.STANDARD,
                HASH_D, NOW.minusSeconds(7200), NOW.minusSeconds(3600), NOW.plusSeconds(7200),
                money("10000"), List.of(account, subaccount),
                List.of(new AccountCapitalAllocation(account.id(), money("10000")),
                        new AccountCapitalAllocation(subaccount.id(), money("5000"))),
                List.of(new ScopedRiskBudget(scope, limits, NOW.plusSeconds(3600))));
    }

    Money money(String amount) {
        return new Money(new BigDecimal(amount), usd);
    }

    PositionValuationSnapshot snapshot(String current, String price) {
        return snapshot(current, price, NOW.minusSeconds(1), NOW.plusSeconds(300),
                SnapshotCompleteness.COMPLETE);
    }

    PositionValuationSnapshot snapshot(
            String current, String price, Instant observedAt, Instant expiresAt,
            SnapshotCompleteness completeness) {
        BigDecimal position = new BigDecimal(current);
        BigDecimal referencePrice = new BigDecimal(price);
        BigDecimal net = position.multiply(referencePrice);
        return new PositionValuationSnapshot(
                account.id(), subaccount.id(), venue, instrumentKey, specification.sha256(),
                new Quantity(position, share), referencePrice, money(net.abs().toPlainString()),
                money(net.toPlainString()), observedAt, expiresAt, completeness, HASH_C);
    }

    TargetPositionPlanningRequest request(String current, String target) {
        return request(snapshot(current, "10"), target, release,
                new OrderPreference(ExecutionOrderType.LIMIT,
                        ExecutionTimeInForce.DAY, new BigDecimal("10.037")),
                NOW.plusSeconds(300));
    }

    TargetPositionPlanningRequest request(
            PositionValuationSnapshot snapshot,
            String target,
            StrategyReleaseBinding strategyRelease,
            OrderPreference preference,
            Instant validUntil) {
        return new TargetPositionPlanningRequest(
                "target-request-1", instrumentKey, specification, account, subaccount, venue,
                new Quantity(new BigDecimal(target), share), snapshot, envelope,
                strategyRelease,
                new ExecutionPolicyBinding(policy.policyId(), policy.version(), policy.sha256(), HASH_C),
                preference, NOW.minusSeconds(1), validUntil);
    }

    VenueExecutionCapabilityVerifier acceptingVerifier() {
        return this::accepted;
    }

    VenueExecutionCapabilityVerification accepted(VenueExecutionCapabilityRequest request) {
        return accepted(request, TradingSessionStatus.OPEN, true, true,
                request.sha256(), request.orderPreference().orderType(),
                request.orderPreference().timeInForce(), request.instrumentKey(),
                request.instrumentSpecification().sha256(), new BigDecimal("0.01"), null,
                null);
    }

    VenueExecutionCapabilityVerification accepted(
            VenueExecutionCapabilityRequest request,
            TradingSessionStatus session,
            boolean shortSupported,
            boolean reduceOnlySupported,
            String capabilityRequestSha256,
            ExecutionOrderType orderType,
            ExecutionTimeInForce timeInForce,
            InstrumentKey boundKey,
            String specificationSha256,
            BigDecimal step,
            BigDecimal forcedNormalizedQuantity,
            BigDecimal forcedNotional) {
        BigDecimal normalizedQuantity = forcedNormalizedQuantity == null
                ? request.absoluteDelta().amount().divide(step, 0, RoundingMode.DOWN).multiply(step)
                : forcedNormalizedQuantity;
        BigDecimal normalizedPrice = null;
        if (request.orderPreference().orderType() == ExecutionOrderType.LIMIT) {
            RoundingMode mode = request.side() == ExecutionSide.BUY
                    ? RoundingMode.FLOOR : RoundingMode.CEILING;
            BigDecimal tick = new BigDecimal("0.05");
            normalizedPrice = request.orderPreference().limitPrice()
                    .divide(tick, 0, mode).multiply(tick);
        }
        BigDecimal notional = forcedNotional == null
                ? normalizedQuantity.multiply(request.referencePrice()).abs()
                : forcedNotional;
        return VenueExecutionCapabilityVerification.verified(
                capabilityRequestSha256, "venue-rules-19", venue, boundKey,
                specificationSha256, orderType, timeInForce, share, step,
                new BigDecimal("0.01"), normalizedQuantity, shortSupported,
                reduceOnlySupported, specification.priceDomain(), new BigDecimal("0.05"),
                normalizedPrice, money(notional.toPlainString()), session,
                NOW.minusSeconds(1), NOW.plusSeconds(180), HASH_D);
    }

    DeterministicExecutionPlanner planner(VenueExecutionCapabilityVerifier verifier) {
        return new DeterministicExecutionPlanner(
                Clock.fixed(NOW, ZoneOffset.UTC), verifier, Duration.ofSeconds(60));
    }
}
