package com.tj.crypto.autonomy.capital;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.List;

import static com.tj.crypto.autonomy.capital.CapitalPolicyTestFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

class EmergencyPolicyReductionTest {
    private CapitalPolicyTestFixtures fixture;
    private CapitalRiskPolicy current;

    @BeforeEach
    void setUp() {
        fixture = new CapitalPolicyTestFixtures();
        current = fixture.initialPolicy();
    }

    @Test
    void acceptsAConsecutiveSignedRevisionThatOnlyReducesRisk() {
        CapitalRiskPolicy candidate = candidate(fixture.money("900"), "900", "300",
                List.of(fixture.budget(CapitalPolicyTestFixtures.SCOPE,
                        fixture.limits("400", "250", "80", "40", "15"),
                        NOW.plusSeconds(3000))), NOW.plusSeconds(6600));

        EmergencyPolicyTransitionDecision result = fixture.kernel().verifyEmergencyReduction(
                fixture.sign(current), fixture.sign(candidate));

        assertThat(result.accepted()).isTrue();
        assertThat(result.code()).isEqualTo(EmergencyTransitionCode.ACCEPTED);
        assertThat(result.currentPolicySha256()).isEqualTo(current.sha256());
        assertThat(result.candidatePolicySha256()).isEqualTo(candidate.sha256());
    }

    @Test
    void rejectsNoOpMasqueradingAsEmergency() {
        CapitalRiskPolicy candidate = candidate(current.portfolioCapital(), "1000", "400",
                current.riskBudgets(), current.expiresAt());

        assertThat(decide(candidate).code()).isEqualTo(EmergencyTransitionCode.NO_RISK_REDUCTION);
    }

    @Test
    void rejectsPortfolioOrAccountCapitalIncrease() {
        CapitalRiskPolicy portfolioIncrease = candidate(fixture.money("1100"), "1000", "400",
                current.riskBudgets(), current.expiresAt());
        CapitalRiskPolicy accountIncrease = candidate(fixture.money("1000"), "1000", "401",
                current.riskBudgets(), current.expiresAt());

        assertThat(decide(portfolioIncrease).code())
                .isEqualTo(EmergencyTransitionCode.PORTFOLIO_CAPITAL_INCREASED);
        assertThat(decide(accountIncrease).code())
                .isEqualTo(EmergencyTransitionCode.ACCOUNT_ALLOCATION_INCREASED);
    }

    @Test
    void rejectsAnySingleRiskLimitIncrease() {
        CapitalRiskPolicy candidate = candidate(current.portfolioCapital(), "1000", "400",
                List.of(fixture.budget(CapitalPolicyTestFixtures.SCOPE,
                        fixture.limits("500.0000000001", "300", "100", "50", "20"),
                        NOW.plusSeconds(3600))), current.expiresAt());

        assertThat(decide(candidate).code()).isEqualTo(EmergencyTransitionCode.RISK_LIMIT_INCREASED);
    }

    @Test
    void rejectsNewScopeEvenWhenAnotherScopeIsReduced() {
        RiskScope addedScope = new RiskScope(CapitalPolicyTestFixtures.SUB.id(),
                CapitalPolicyTestFixtures.VENUE, CapitalPolicyTestFixtures.INSTRUMENT, "new-strategy");
        ArrayList<ScopedRiskBudget> budgets = new ArrayList<>();
        budgets.add(fixture.budget(CapitalPolicyTestFixtures.SCOPE,
                fixture.limits("400", "250", "80", "40", "15"), NOW.plusSeconds(3000)));
        budgets.add(fixture.budget(addedScope,
                fixture.limits("1", "1", "1", "1", "1"), NOW.plusSeconds(3000)));
        CapitalRiskPolicy candidate = candidate(fixture.money("1000"), "1000", "400",
                budgets, current.expiresAt());

        assertThat(decide(candidate).code()).isEqualTo(EmergencyTransitionCode.RISK_SCOPE_ADDED);
    }

    @Test
    void rejectsExtensionOfPolicyOrScopeValidity() {
        CapitalRiskPolicy policyExtended = candidate(current.portfolioCapital(), "1000", "400",
                current.riskBudgets(), current.expiresAt().plusSeconds(1));
        CapitalRiskPolicy scopeExtended = candidate(current.portfolioCapital(), "1000", "400",
                List.of(fixture.budget(CapitalPolicyTestFixtures.SCOPE,
                        current.riskBudgets().get(0).limits(), NOW.plusSeconds(3601))),
                current.expiresAt());

        assertThat(decide(policyExtended).code()).isEqualTo(EmergencyTransitionCode.VALIDITY_EXTENDED);
        assertThat(decide(scopeExtended).code())
                .isEqualTo(EmergencyTransitionCode.RISK_BUDGET_VALIDITY_EXTENDED);
    }

    @Test
    void rejectsSkippedVersionAndWrongPredecessor() {
        CapitalRiskPolicy skipped = fixture.policy(3, PolicyRevisionKind.EMERGENCY_REDUCTION,
                current.sha256(), fixture.money("900"),
                List.of(CapitalPolicyTestFixtures.ROOT, CapitalPolicyTestFixtures.SUB),
                allocations("900", "300"), current.riskBudgets(), current.expiresAt());
        CapitalRiskPolicy wrongPredecessor = fixture.policy(2, PolicyRevisionKind.EMERGENCY_REDUCTION,
                "a".repeat(64), fixture.money("900"),
                List.of(CapitalPolicyTestFixtures.ROOT, CapitalPolicyTestFixtures.SUB),
                allocations("900", "300"), current.riskBudgets(), current.expiresAt());

        assertThat(decide(skipped).code()).isEqualTo(EmergencyTransitionCode.VERSION_NOT_CONSECUTIVE);
        assertThat(decide(wrongPredecessor).code()).isEqualTo(EmergencyTransitionCode.PREDECESSOR_MISMATCH);
    }

    @Test
    void rejectsAccountTopologyChanges() {
        Subaccount replacement = new Subaccount("replacement-sub",
                CapitalPolicyTestFixtures.ROOT.id(), CapitalPolicyTestFixtures.VENUE);
        RiskScope replacementScope = new RiskScope(replacement.id(), CapitalPolicyTestFixtures.VENUE,
                CapitalPolicyTestFixtures.INSTRUMENT, CapitalPolicyTestFixtures.SCOPE.strategyId());
        CapitalRiskPolicy candidate = fixture.policy(2, PolicyRevisionKind.EMERGENCY_REDUCTION,
                current.sha256(), fixture.money("900"),
                List.of(CapitalPolicyTestFixtures.ROOT, replacement),
                List.of(new AccountCapitalAllocation(CapitalPolicyTestFixtures.ROOT.id(), fixture.money("900")),
                        new AccountCapitalAllocation(replacement.id(), fixture.money("300"))),
                List.of(fixture.budget(replacementScope,
                        fixture.limits("400", "250", "80", "40", "15"), NOW.plusSeconds(3000))),
                current.expiresAt());

        assertThat(decide(candidate).code())
                .isEqualTo(EmergencyTransitionCode.ACCOUNT_TOPOLOGY_CHANGED);
    }

    @Test
    void rejectsCandidateSignedByAnUntrustedKey() throws Exception {
        CapitalRiskPolicy candidate = candidate(fixture.money("900"), "900", "300",
                current.riskBudgets(), current.expiresAt());
        KeyPair attacker = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();

        EmergencyPolicyTransitionDecision result = fixture.kernel().verifyEmergencyReduction(
                fixture.sign(current), fixture.sign(candidate, "attacker", attacker));

        assertThat(result.code()).isEqualTo(EmergencyTransitionCode.UNTRUSTED_CANDIDATE_POLICY);
    }

    private CapitalRiskPolicy candidate(Money capital, String rootCapital, String subCapital,
                                        List<ScopedRiskBudget> budgets,
                                        java.time.Instant expiry) {
        return fixture.policy(2, PolicyRevisionKind.EMERGENCY_REDUCTION, current.sha256(), capital,
                List.of(CapitalPolicyTestFixtures.ROOT, CapitalPolicyTestFixtures.SUB),
                allocations(rootCapital, subCapital), budgets, expiry);
    }

    private List<AccountCapitalAllocation> allocations(String rootCapital, String subCapital) {
        return List.of(
                new AccountCapitalAllocation(CapitalPolicyTestFixtures.ROOT.id(),
                        fixture.money(rootCapital)),
                new AccountCapitalAllocation(CapitalPolicyTestFixtures.SUB.id(),
                        fixture.money(subCapital)));
    }

    private EmergencyPolicyTransitionDecision decide(CapitalRiskPolicy candidate) {
        return fixture.kernel().verifyEmergencyReduction(
                fixture.sign(current), fixture.sign(candidate));
    }
}
