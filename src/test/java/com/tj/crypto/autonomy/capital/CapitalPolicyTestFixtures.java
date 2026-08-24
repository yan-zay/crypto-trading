package com.tj.crypto.autonomy.capital;

import java.math.BigDecimal;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

final class CapitalPolicyTestFixtures {
    static final Instant NOW = Instant.parse("2026-08-24T08:00:00Z");
    static final AssetUnit USD = new AssetUnit("USD");
    static final Venue VENUE = new Venue("broker-primary");
    static final Venue OTHER_VENUE = new Venue("regulated-venue-b");
    static final Account ROOT = new Account("master-1", VENUE);
    static final Subaccount SUB = new Subaccount("sub-quant-1", ROOT.id(), VENUE);
    static final InstrumentKey INSTRUMENT_KEY = new InstrumentKey(
            new AssetClass("equity"), "FIGI", "BBG000B9XRY4");
    static final InstrumentSpecification INSTRUMENT_SPECIFICATION = new InstrumentSpecification(
            INSTRUMENT_KEY, new QuantityUnit("share"), PriceDomain.POSITIVE_ONLY,
            new InstrumentValuation("linear_unit_price", USD, USD,
                    new BigDecimal("1"), "terms-2026-01"), "spec-1");
    static final Instrument INSTRUMENT = new Instrument(INSTRUMENT_KEY, INSTRUMENT_SPECIFICATION);
    static final RiskScope SCOPE = new RiskScope(SUB.id(), VENUE, INSTRUMENT, "strategy-quality-v1");

    final KeyPair keyPair;

    CapitalPolicyTestFixtures() {
        try {
            keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    Money money(String value) {
        return new Money(new BigDecimal(value), USD);
    }

    RiskLimits limits(String gross, String net, String order, String dailyLoss, String stopLoss) {
        return new RiskLimits(money(gross), money(net), money(order), money(dailyLoss), money(stopLoss));
    }

    ScopedRiskBudget budget(RiskScope scope, RiskLimits limits, Instant expiry) {
        return new ScopedRiskBudget(scope, limits, expiry);
    }

    CapitalRiskPolicy initialPolicy() {
        return policy(1, PolicyRevisionKind.STANDARD, null, money("1000"),
                List.of(ROOT, SUB),
                List.of(new AccountCapitalAllocation(ROOT.id(), money("1000")),
                        new AccountCapitalAllocation(SUB.id(), money("400"))),
                List.of(budget(SCOPE, limits("500", "300", "100", "50", "20"),
                        NOW.plusSeconds(3600))),
                NOW.plusSeconds(7200));
    }

    CapitalRiskPolicy policy(long version,
                             PolicyRevisionKind kind,
                             String predecessor,
                             Money portfolioCapital,
                             List<? extends AccountNode> accounts,
                             List<AccountCapitalAllocation> allocations,
                             List<ScopedRiskBudget> budgets,
                             Instant policyExpiry) {
        return new CapitalRiskPolicy("global-capital-policy", version, "portfolio-alpha", kind,
                predecessor, NOW.minusSeconds(version == 1 ? 3600 : 120),
                NOW.minusSeconds(version == 1 ? 1800 : 60), policyExpiry,
                portfolioCapital, accounts, allocations, budgets);
    }

    SignedCapitalRiskPolicy sign(CapitalRiskPolicy policy) {
        return sign(policy, "capital-root-1", keyPair);
    }

    SignedCapitalRiskPolicy sign(CapitalRiskPolicy policy, String keyId, KeyPair pair) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(pair.getPrivate());
            signer.update(policy.canonicalBytes());
            return new SignedCapitalRiskPolicy(policy, keyId, signer.sign());
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    CapitalPolicyTrustRoot trustRoot() {
        return new CapitalPolicyTrustRoot(List.of(new TrustedPolicyKey(
                "capital-root-1", keyPair.getPublic(), NOW.minusSeconds(86400),
                NOW.plusSeconds(86400))));
    }

    CapitalPolicyKernel kernel() {
        return kernel(sign(initialPolicy()));
    }

    CapitalPolicyKernel kernel(CapitalRiskPolicy activePolicy) {
        return kernel(sign(activePolicy));
    }

    CapitalPolicyKernel kernel(SignedCapitalRiskPolicy activePolicy) {
        ActiveCapitalPolicyResolver resolver = (portfolioId, policyId) -> Optional.of(activePolicy);
        return new CapitalPolicyKernel(Clock.fixed(NOW, ZoneOffset.UTC), trustRoot(), resolver);
    }

    CapitalResolutionRequest request(CapitalRiskPolicy policy, RiskScope scope, String capital) {
        return new CapitalResolutionRequest(policy.portfolioId(), policy.policyId(), policy.version(),
                policy.sha256(), scope, money(capital));
    }
}
