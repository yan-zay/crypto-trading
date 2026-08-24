package com.tj.crypto.autonomy.capital;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static com.tj.crypto.autonomy.capital.CapitalPolicyTestFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

class CapitalPolicyKernelTest {
    private CapitalPolicyTestFixtures fixture;
    private CapitalRiskPolicy policy;

    @BeforeEach
    void setUp() {
        fixture = new CapitalPolicyTestFixtures();
        policy = fixture.initialPolicy();
    }

    @Test
    void resolvesOnlyAnExactSignedAndCurrentEnvelope() {
        CapitalEnvelopeResolution result = fixture.kernel(policy).resolve(
                fixture.request(policy, CapitalPolicyTestFixtures.SCOPE, "400.000"));

        assertThat(result.resolved()).isTrue();
        assertThat(result.code()).isEqualTo(CapitalResolutionCode.RESOLVED);
        assertThat(result.policySha256()).isEqualTo(policy.sha256());
        assertThat(result.allocatedCapital()).isEqualTo(fixture.money("400"));
        assertThat(result.riskLimits().maxDailyLoss()).isEqualTo(fixture.money("50"));
        assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(result.executionAuthorized()).isFalse();
    }

    @Test
    void rejectsAValidSignatureAttachedToDifferentPolicyContent() {
        SignedCapitalRiskPolicy original = fixture.sign(policy);
        CapitalRiskPolicy changed = fixture.policy(1, PolicyRevisionKind.STANDARD, null,
                fixture.money("1000"), List.of(CapitalPolicyTestFixtures.ROOT, CapitalPolicyTestFixtures.SUB),
                List.of(new AccountCapitalAllocation(CapitalPolicyTestFixtures.ROOT.id(), fixture.money("1000")),
                        new AccountCapitalAllocation(CapitalPolicyTestFixtures.SUB.id(), fixture.money("399"))),
                policy.riskBudgets(), policy.expiresAt());
        SignedCapitalRiskPolicy tampered = new SignedCapitalRiskPolicy(
                changed, original.keyId(), original.signature());

        CapitalEnvelopeResolution result = fixture.kernel(tampered).resolve(
                fixture.request(changed, CapitalPolicyTestFixtures.SCOPE, "1"));

        assertThat(result.code()).isEqualTo(CapitalResolutionCode.UNTRUSTED_POLICY);
        assertThat(result.resolved()).isFalse();
    }

    @Test
    void rejectsSignatureFromAKeyOutsideThePinnedTrustRoot() throws Exception {
        KeyPair attacker = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SignedCapitalRiskPolicy attackerSigned = fixture.sign(policy, "attacker-key", attacker);

        CapitalEnvelopeResolution result = fixture.kernel(attackerSigned).resolve(
                fixture.request(policy, CapitalPolicyTestFixtures.SCOPE, "1"));

        assertThat(result.code()).isEqualTo(CapitalResolutionCode.UNTRUSTED_POLICY);
    }

    @Test
    void rejectsAnExpiredTrustKeyEvenWhenTheSignatureIsCryptographicallyValid() {
        CapitalPolicyTrustRoot expiredRoot = new CapitalPolicyTrustRoot(List.of(
                new TrustedPolicyKey("capital-root-1", fixture.keyPair.getPublic(),
                        NOW.minusSeconds(7200), NOW.minusSeconds(1))));
        SignedCapitalRiskPolicy signed = fixture.sign(policy);
        CapitalPolicyKernel kernel = new CapitalPolicyKernel(
                Clock.fixed(NOW, ZoneOffset.UTC), expiredRoot,
                (portfolioId, policyId) -> Optional.of(signed));

        assertThat(kernel.resolve(
                fixture.request(policy, CapitalPolicyTestFixtures.SCOPE, "1")).code())
                .isEqualTo(CapitalResolutionCode.UNTRUSTED_POLICY);
    }

    @Test
    void rejectsExpiredPolicyAndExpiredScopedBudget() {
        SignedCapitalRiskPolicy signed = fixture.sign(policy);
        CapitalPolicyKernel afterPolicyExpiry = new CapitalPolicyKernel(
                Clock.fixed(policy.expiresAt(), ZoneOffset.UTC), fixture.trustRoot(),
                (portfolioId, policyId) -> Optional.of(signed));
        assertThat(afterPolicyExpiry.resolve(
                fixture.request(policy, CapitalPolicyTestFixtures.SCOPE, "1")).code())
                .isEqualTo(CapitalResolutionCode.POLICY_NOT_CURRENT);

        CapitalRiskPolicy budgetExpired = fixture.policy(1, PolicyRevisionKind.STANDARD, null,
                fixture.money("1000"), List.of(CapitalPolicyTestFixtures.ROOT, CapitalPolicyTestFixtures.SUB),
                policy.accountAllocations(),
                List.of(fixture.budget(CapitalPolicyTestFixtures.SCOPE,
                        fixture.limits("500", "300", "100", "50", "20"),
                        NOW.minusSeconds(1))), policy.expiresAt());
        assertThat(fixture.kernel(budgetExpired).resolve(
                fixture.request(budgetExpired, CapitalPolicyTestFixtures.SCOPE, "1")).code())
                .isEqualTo(CapitalResolutionCode.RISK_BUDGET_EXPIRED);
    }

    @Test
    void rejectsEveryPolicyIdentityMismatch() {
        SignedCapitalRiskPolicy signed = fixture.sign(policy);
        CapitalPolicyKernel kernel = fixture.kernel(signed);
        CapitalResolutionRequest wrongId = new CapitalResolutionRequest(
                policy.portfolioId(), "other-policy", policy.version(), policy.sha256(),
                CapitalPolicyTestFixtures.SCOPE,
                fixture.money("1"));
        CapitalResolutionRequest wrongVersion = new CapitalResolutionRequest(
                policy.portfolioId(), policy.policyId(), 99, policy.sha256(),
                CapitalPolicyTestFixtures.SCOPE, fixture.money("1"));
        CapitalResolutionRequest wrongDigest = new CapitalResolutionRequest(
                policy.portfolioId(), policy.policyId(), policy.version(), "f".repeat(64),
                CapitalPolicyTestFixtures.SCOPE, fixture.money("1"));
        CapitalResolutionRequest wrongPortfolio = new CapitalResolutionRequest(
                "other-portfolio", policy.policyId(), policy.version(), policy.sha256(),
                CapitalPolicyTestFixtures.SCOPE, fixture.money("1"));

        assertThat(kernel.resolve(wrongId).code())
                .isEqualTo(CapitalResolutionCode.POLICY_ID_MISMATCH);
        assertThat(kernel.resolve(wrongVersion).code())
                .isEqualTo(CapitalResolutionCode.POLICY_VERSION_MISMATCH);
        assertThat(kernel.resolve(wrongDigest).code())
                .isEqualTo(CapitalResolutionCode.POLICY_DIGEST_MISMATCH);
        assertThat(kernel.resolve(wrongPortfolio).code())
                .isEqualTo(CapitalResolutionCode.PORTFOLIO_ID_MISMATCH);
    }

    @Test
    void rejectsNearMatchScopeForDifferentVenueInstrumentOrStrategy() {
        InstrumentKey otherKey = new InstrumentKey(new AssetClass("equity"), "FIGI", "DIFFERENT");
        Instrument otherInstrument = new Instrument(otherKey,
                new InstrumentSpecification(otherKey, new QuantityUnit("share"),
                        PriceDomain.POSITIVE_ONLY,
                        CapitalPolicyTestFixtures.INSTRUMENT_SPECIFICATION.valuation(), "spec-1"));
        RiskScope wrongVenue = new RiskScope(CapitalPolicyTestFixtures.SUB.id(),
                CapitalPolicyTestFixtures.OTHER_VENUE, CapitalPolicyTestFixtures.INSTRUMENT,
                CapitalPolicyTestFixtures.SCOPE.strategyId());
        RiskScope wrongInstrument = new RiskScope(CapitalPolicyTestFixtures.SUB.id(),
                CapitalPolicyTestFixtures.VENUE, otherInstrument,
                CapitalPolicyTestFixtures.SCOPE.strategyId());
        RiskScope wrongStrategy = new RiskScope(CapitalPolicyTestFixtures.SUB.id(),
                CapitalPolicyTestFixtures.VENUE, CapitalPolicyTestFixtures.INSTRUMENT, "other-strategy");
        SignedCapitalRiskPolicy signed = fixture.sign(policy);

        CapitalPolicyKernel kernel = fixture.kernel(signed);
        assertThat(kernel.resolve(fixture.request(policy, wrongVenue, "1")).code())
                .isEqualTo(CapitalResolutionCode.ACCOUNT_VENUE_MISMATCH);
        assertThat(kernel.resolve(fixture.request(policy, wrongInstrument, "1")).code())
                .isEqualTo(CapitalResolutionCode.SCOPE_NOT_ALLOCATED);
        assertThat(kernel.resolve(fixture.request(policy, wrongStrategy, "1")).code())
                .isEqualTo(CapitalResolutionCode.SCOPE_NOT_ALLOCATED);
    }

    @Test
    void rejectsWrongValuationUnitAndCapitalAboveTheAccountAllocation() {
        Money euros = new Money(BigDecimal.ONE, new AssetUnit("EUR"));
        CapitalResolutionRequest wrongUnit = new CapitalResolutionRequest(policy.portfolioId(),
                policy.policyId(), policy.version(), policy.sha256(),
                CapitalPolicyTestFixtures.SCOPE, euros);

        CapitalPolicyKernel kernel = fixture.kernel(policy);
        assertThat(kernel.resolve(wrongUnit).code())
                .isEqualTo(CapitalResolutionCode.VALUATION_UNIT_MISMATCH);
        assertThat(kernel.resolve(
                fixture.request(policy, CapitalPolicyTestFixtures.SCOPE, "400.0000000001")).code())
                .isEqualTo(CapitalResolutionCode.CAPITAL_ALLOCATION_EXCEEDED);
    }

    @Test
    void resolvesAnIndependentlyReconstructedButContentIdenticalSpecification() {
        InstrumentSpecification rebuiltSpecification = new InstrumentSpecification(
                CapitalPolicyTestFixtures.INSTRUMENT_KEY, new QuantityUnit("SHARE"),
                PriceDomain.POSITIVE_ONLY,
                new InstrumentValuation("LINEAR_UNIT_PRICE", CapitalPolicyTestFixtures.USD,
                        CapitalPolicyTestFixtures.USD, new BigDecimal("1.000"), "terms-2026-01"),
                "spec-1");
        RiskScope reconstructed = new RiskScope(CapitalPolicyTestFixtures.SUB.id(),
                CapitalPolicyTestFixtures.VENUE,
                new Instrument(CapitalPolicyTestFixtures.INSTRUMENT_KEY, rebuiltSpecification),
                CapitalPolicyTestFixtures.SCOPE.strategyId());

        assertThat(rebuiltSpecification).isEqualTo(
                CapitalPolicyTestFixtures.INSTRUMENT_SPECIFICATION);
        assertThat(fixture.kernel(policy).resolve(
                fixture.request(policy, reconstructed, "1")).code())
                .isEqualTo(CapitalResolutionCode.RESOLVED);
    }

    @Test
    void failsClosedWhenTheServerOwnedActiveHeadIsUnavailableOrThrows() {
        CapitalPolicyKernel unavailable = new CapitalPolicyKernel(
                Clock.fixed(NOW, ZoneOffset.UTC), fixture.trustRoot(),
                ActiveCapitalPolicyResolver.unavailable());
        CapitalPolicyKernel failed = new CapitalPolicyKernel(
                Clock.fixed(NOW, ZoneOffset.UTC), fixture.trustRoot(),
                (portfolioId, policyId) -> { throw new IllegalStateException("store unavailable"); });

        assertThat(unavailable.resolve(fixture.request(
                policy, CapitalPolicyTestFixtures.SCOPE, "1")).code())
                .isEqualTo(CapitalResolutionCode.ACTIVE_POLICY_UNAVAILABLE);
        assertThat(failed.resolve(fixture.request(
                policy, CapitalPolicyTestFixtures.SCOPE, "1")).code())
                .isEqualTo(CapitalResolutionCode.ACTIVE_POLICY_UNAVAILABLE);
    }

    @Test
    void callerCannotSelectAnOlderStillUnexpiredPolicyInsteadOfTheActiveHead() {
        CapitalRiskPolicy activeV2 = fixture.policy(2, PolicyRevisionKind.EMERGENCY_REDUCTION,
                policy.sha256(), fixture.money("900"),
                List.of(CapitalPolicyTestFixtures.ROOT, CapitalPolicyTestFixtures.SUB),
                List.of(new AccountCapitalAllocation(CapitalPolicyTestFixtures.ROOT.id(), fixture.money("900")),
                        new AccountCapitalAllocation(CapitalPolicyTestFixtures.SUB.id(), fixture.money("300"))),
                policy.riskBudgets(), policy.expiresAt());

        CapitalEnvelopeResolution result = fixture.kernel(activeV2).resolve(
                fixture.request(policy, CapitalPolicyTestFixtures.SCOPE, "1"));

        assertThat(policy.currentAt(NOW)).isTrue();
        assertThat(result.code()).isEqualTo(CapitalResolutionCode.POLICY_VERSION_MISMATCH);
        assertThat(result.resolved()).isFalse();
    }
}
