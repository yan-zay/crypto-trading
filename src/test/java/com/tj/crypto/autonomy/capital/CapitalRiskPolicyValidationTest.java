package com.tj.crypto.autonomy.capital;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.tj.crypto.autonomy.capital.CapitalPolicyTestFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapitalRiskPolicyValidationTest {
    private CapitalPolicyTestFixtures fixture;

    @BeforeEach
    void setUp() {
        fixture = new CapitalPolicyTestFixtures();
    }

    @Test
    void moneyUsesExactDecimalArithmeticAndRejectsImplicitUnitConversion() {
        Money result = fixture.money("0.10").add(fixture.money("0.2000"));

        assertThat(result).isEqualTo(fixture.money("0.3"));
        assertThat(result.amount().toPlainString()).isEqualTo("0.3");
        assertThatThrownBy(() -> result.add(new Money(BigDecimal.ONE, new AssetUnit("EUR"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("units differ");
    }

    @Test
    void policyHashIsOrderAndScaleIndependentButBindsEveryEconomicTerm() {
        CapitalRiskPolicy first = fixture.initialPolicy();
        CapitalRiskPolicy reordered = fixture.policy(1, PolicyRevisionKind.STANDARD, null,
                fixture.money("1000.000"),
                List.of(CapitalPolicyTestFixtures.SUB, CapitalPolicyTestFixtures.ROOT),
                List.of(new AccountCapitalAllocation(CapitalPolicyTestFixtures.SUB.id(), fixture.money("400.0")),
                        new AccountCapitalAllocation(CapitalPolicyTestFixtures.ROOT.id(), fixture.money("1000.00"))),
                first.riskBudgets(), first.expiresAt());

        InstrumentSpecification changedTerms = new InstrumentSpecification(
                CapitalPolicyTestFixtures.INSTRUMENT_KEY, new QuantityUnit("share"),
                PriceDomain.POSITIVE_ONLY,
                new InstrumentValuation("linear_unit_price", CapitalPolicyTestFixtures.USD,
                        CapitalPolicyTestFixtures.USD, new BigDecimal("2"), "terms-2026-02"), "spec-2");
        Instrument changedInstrument = new Instrument(
                CapitalPolicyTestFixtures.INSTRUMENT_KEY, changedTerms);
        RiskScope changedScope = new RiskScope(CapitalPolicyTestFixtures.SUB.id(),
                CapitalPolicyTestFixtures.VENUE, changedInstrument,
                CapitalPolicyTestFixtures.SCOPE.strategyId());
        CapitalRiskPolicy changed = fixture.policy(1, PolicyRevisionKind.STANDARD, null,
                fixture.money("1000"), first.accounts(), first.accountAllocations(),
                List.of(fixture.budget(changedScope, first.riskBudgets().get(0).limits(),
                        first.riskBudgets().get(0).expiresAt())), first.expiresAt());

        assertThat(reordered.sha256()).isEqualTo(first.sha256());
        assertThat(changedInstrument.canonicalId())
                .isEqualTo(CapitalPolicyTestFixtures.INSTRUMENT.canonicalId());
        assertThat(changedInstrument.specification().sha256())
                .isNotEqualTo(CapitalPolicyTestFixtures.INSTRUMENT.specification().sha256());
        assertThat(changed.sha256()).isNotEqualTo(first.sha256());
    }

    @Test
    void rejectsMissingParentCrossVenueAndCycles() {
        Subaccount orphan = new Subaccount("orphan", "missing", CapitalPolicyTestFixtures.VENUE);
        assertThatThrownBy(() -> policyWithAccounts(List.of(CapitalPolicyTestFixtures.ROOT, orphan),
                List.of(allocation(CapitalPolicyTestFixtures.ROOT.id(), "1000"),
                        allocation(orphan.id(), "1"))))
                .hasMessageContaining("missing parent");

        Subaccount crossVenue = new Subaccount("cross", CapitalPolicyTestFixtures.ROOT.id(),
                CapitalPolicyTestFixtures.OTHER_VENUE);
        assertThatThrownBy(() -> policyWithAccounts(List.of(CapitalPolicyTestFixtures.ROOT, crossVenue),
                List.of(allocation(CapitalPolicyTestFixtures.ROOT.id(), "1000"),
                        allocation(crossVenue.id(), "1"))))
                .hasMessageContaining("same venue");

        Subaccount first = new Subaccount("cycle-1", "cycle-2", CapitalPolicyTestFixtures.VENUE);
        Subaccount second = new Subaccount("cycle-2", "cycle-1", CapitalPolicyTestFixtures.VENUE);
        assertThatThrownBy(() -> policyWithAccounts(
                List.of(CapitalPolicyTestFixtures.ROOT, first, second),
                List.of(allocation(CapitalPolicyTestFixtures.ROOT.id(), "1000"),
                        allocation(first.id(), "1"), allocation(second.id(), "1"))))
                .hasMessageContaining("cycle");
    }

    @Test
    void rejectsIncompleteOrOverAllocatedAccountTrees() {
        assertThatThrownBy(() -> fixture.policy(1, PolicyRevisionKind.STANDARD, null,
                fixture.money("1000"),
                List.of(CapitalPolicyTestFixtures.ROOT, CapitalPolicyTestFixtures.SUB),
                List.of(allocation(CapitalPolicyTestFixtures.ROOT.id(), "1000")),
                List.of(), NOW.plusSeconds(7200)))
                .hasMessageContaining("every account");

        assertThatThrownBy(() -> fixture.policy(1, PolicyRevisionKind.STANDARD, null,
                fixture.money("1000"),
                List.of(CapitalPolicyTestFixtures.ROOT, CapitalPolicyTestFixtures.SUB),
                List.of(allocation(CapitalPolicyTestFixtures.ROOT.id(), "1000"),
                        allocation(CapitalPolicyTestFixtures.SUB.id(), "1000.0000000001")),
                List.of(), NOW.plusSeconds(7200)))
                .hasMessageContaining("child allocations exceed parent");

        Account secondRoot = new Account("root-2", CapitalPolicyTestFixtures.OTHER_VENUE);
        assertThatThrownBy(() -> policyWithAccounts(
                List.of(CapitalPolicyTestFixtures.ROOT, secondRoot),
                List.of(allocation(CapitalPolicyTestFixtures.ROOT.id(), "600"),
                        allocation(secondRoot.id(), "500"))))
                .hasMessageContaining("root allocations exceed");
    }

    @Test
    void rejectsUnknownAccountWrongVenueDuplicateScopeAndUnitMismatch() {
        CapitalRiskPolicy base = fixture.initialPolicy();
        RiskScope unknownAccount = new RiskScope("not-allocated", CapitalPolicyTestFixtures.VENUE,
                CapitalPolicyTestFixtures.INSTRUMENT, "strategy");
        assertThatThrownBy(() -> policyWithBudgets(List.of(fixture.budget(unknownAccount,
                base.riskBudgets().get(0).limits(), NOW.plusSeconds(3600)))))
                .hasMessageContaining("unknown account");

        RiskScope wrongVenue = new RiskScope(CapitalPolicyTestFixtures.SUB.id(),
                CapitalPolicyTestFixtures.OTHER_VENUE, CapitalPolicyTestFixtures.INSTRUMENT, "strategy");
        assertThatThrownBy(() -> policyWithBudgets(List.of(fixture.budget(wrongVenue,
                base.riskBudgets().get(0).limits(), NOW.plusSeconds(3600)))))
                .hasMessageContaining("venue differs");

        assertThatThrownBy(() -> policyWithBudgets(List.of(
                base.riskBudgets().get(0), base.riskBudgets().get(0))))
                .hasMessageContaining("duplicate risk scope");

        InstrumentSpecification rebuilt = new InstrumentSpecification(
                CapitalPolicyTestFixtures.INSTRUMENT_KEY, new QuantityUnit("SHARE"),
                PriceDomain.POSITIVE_ONLY,
                new InstrumentValuation("LINEAR_UNIT_PRICE", CapitalPolicyTestFixtures.USD,
                        CapitalPolicyTestFixtures.USD, BigDecimal.ONE, "terms-2026-01"), "spec-1");
        RiskScope sameContent = new RiskScope(CapitalPolicyTestFixtures.SUB.id(),
                CapitalPolicyTestFixtures.VENUE,
                new Instrument(CapitalPolicyTestFixtures.INSTRUMENT_KEY, rebuilt),
                CapitalPolicyTestFixtures.SCOPE.strategyId());
        assertThatThrownBy(() -> policyWithBudgets(List.of(
                base.riskBudgets().get(0),
                fixture.budget(sameContent, base.riskBudgets().get(0).limits(),
                        base.riskBudgets().get(0).expiresAt()))))
                .hasMessageContaining("duplicate risk scope");

        AssetUnit eur = new AssetUnit("EUR");
        RiskLimits eurLimits = new RiskLimits(
                new Money(BigDecimal.TEN, eur), new Money(BigDecimal.TEN, eur),
                new Money(BigDecimal.ONE, eur), new Money(BigDecimal.ONE, eur),
                new Money(BigDecimal.ONE, eur));
        assertThatThrownBy(() -> policyWithBudgets(List.of(fixture.budget(
                CapitalPolicyTestFixtures.SCOPE, eurLimits, NOW.plusSeconds(3600)))))
                .hasMessageContaining("different valuation unit");
    }

    @Test
    void rejectsInternallyInconsistentRiskLimits() {
        assertThatThrownBy(() -> fixture.limits("100", "101", "1", "10", "1"))
                .hasMessageContaining("maxNetNotional");
        assertThatThrownBy(() -> fixture.limits("100", "50", "101", "10", "1"))
                .hasMessageContaining("maxSingleOrderNotional");
        assertThatThrownBy(() -> fixture.limits("100", "50", "10", "10", "11"))
                .hasMessageContaining("maxLossAtStop");
    }

    private AccountCapitalAllocation allocation(String accountId, String amount) {
        return new AccountCapitalAllocation(accountId, fixture.money(amount));
    }

    private CapitalRiskPolicy policyWithAccounts(List<? extends AccountNode> accounts,
                                                 List<AccountCapitalAllocation> allocations) {
        return fixture.policy(1, PolicyRevisionKind.STANDARD, null, fixture.money("1000"),
                accounts, allocations, List.of(), NOW.plusSeconds(7200));
    }

    private CapitalRiskPolicy policyWithBudgets(List<ScopedRiskBudget> budgets) {
        CapitalRiskPolicy base = fixture.initialPolicy();
        return fixture.policy(1, PolicyRevisionKind.STANDARD, null, fixture.money("1000"),
                base.accounts(), base.accountAllocations(), budgets, base.expiresAt());
    }
}
