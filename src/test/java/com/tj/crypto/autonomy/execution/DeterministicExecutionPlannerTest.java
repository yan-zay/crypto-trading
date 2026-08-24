package com.tj.crypto.autonomy.execution;

import com.tj.crypto.autonomy.capital.AssetClass;
import com.tj.crypto.autonomy.capital.InstrumentKey;
import com.tj.crypto.autonomy.capital.Quantity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeterministicExecutionPlannerTest {
    private ExecutionPlannerTestFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new ExecutionPlannerTestFixture();
    }

    @Test
    void compilesExactDeltaIntoShortLivedContentAddressedCandidate() {
        TargetPositionPlanningRequest request = fixture.request("5", "12.037");

        ExecutionPlan plan = fixture.planner(fixture.acceptingVerifier()).plan(request);

        assertThat(plan.code()).isEqualTo(ExecutionPlanningCode.CANDIDATE_CREATED);
        ExecutionCandidate candidate = plan.candidate().orElseThrow();
        assertThat(candidate.side()).isEqualTo(ExecutionSide.BUY);
        assertThat(candidate.quantity()).isEqualTo(
                new Quantity(new BigDecimal("7.03"), fixture.share));
        assertThat(candidate.limitPrice()).isEqualByComparingTo("10.00");
        assertThat(candidate.orderNotional()).isEqualTo(fixture.money("70.3"));
        assertThat(candidate.expiresAt()).isEqualTo(ExecutionPlannerTestFixture.NOW.plusSeconds(60));
        assertThat(candidate.planningRequestSha256()).isEqualTo(request.sha256());
        assertThat(candidate.candidateId()).isEqualTo(
                "execution-candidate:sha256:" + candidate.sha256());
        assertThat(candidate.dispatchAuthorized()).isFalse();
        assertThat(plan.dispatchAuthorized()).isFalse();
    }

    @Test
    void equivalentDecimalScalesProduceIdenticalContentAddresses() {
        TargetPositionPlanningRequest first = fixture.request("5.0", "12.0300");
        TargetPositionPlanningRequest second = fixture.request("5.00", "12.03");

        ExecutionPlan firstPlan = fixture.planner(fixture.acceptingVerifier()).plan(first);
        ExecutionPlan secondPlan = fixture.planner(fixture.acceptingVerifier()).plan(second);

        assertThat(first.sha256()).isEqualTo(second.sha256());
        assertThat(firstPlan.candidate().orElseThrow().sha256())
                .isEqualTo(secondPlan.candidate().orElseThrow().sha256());
    }

    @Test
    void defaultPlannerFailsClosedWithoutTrustedVenueCapability() {
        ExecutionPlan plan = new DeterministicExecutionPlanner(
                Clock.fixed(ExecutionPlannerTestFixture.NOW, java.time.ZoneOffset.UTC))
                .plan(fixture.request("0", "1"));

        assertThat(plan.code()).isEqualTo(ExecutionPlanningCode.CAPABILITY_UNAVAILABLE);
        assertThat(plan.candidate()).isEmpty();
    }

    @Test
    void rejectsIncompleteAndExpiredPointInTimeFactsBeforeCallingVerifier() {
        PositionValuationSnapshot incomplete = fixture.snapshot(
                "0", "10", ExecutionPlannerTestFixture.NOW.minusSeconds(2),
                ExecutionPlannerTestFixture.NOW.plusSeconds(2), SnapshotCompleteness.PARTIAL);
        PositionValuationSnapshot expired = fixture.snapshot(
                "0", "10", ExecutionPlannerTestFixture.NOW.minusSeconds(20),
                ExecutionPlannerTestFixture.NOW.minusSeconds(1), SnapshotCompleteness.COMPLETE);
        VenueExecutionCapabilityVerifier mustNotRun = request -> {
            throw new AssertionError("verifier must not run");
        };

        assertThat(fixture.planner(mustNotRun).plan(fixture.request(
                incomplete, "1", fixture.release,
                new OrderPreference(ExecutionOrderType.MARKET,
                        ExecutionTimeInForce.DAY, null),
                ExecutionPlannerTestFixture.NOW.plusSeconds(10))).code())
                .isEqualTo(ExecutionPlanningCode.SNAPSHOT_INCOMPLETE);
        assertThat(fixture.planner(mustNotRun).plan(fixture.request(
                expired, "1", fixture.release,
                new OrderPreference(ExecutionOrderType.MARKET,
                        ExecutionTimeInForce.DAY, null),
                ExecutionPlannerTestFixture.NOW.plusSeconds(10))).code())
                .isEqualTo(ExecutionPlanningCode.SNAPSHOT_NOT_CURRENT);
    }

    @Test
    void rejectsStrategyScopeNearMatchAndPositionReversal() {
        StrategyReleaseBinding wrongStrategy = new StrategyReleaseBinding(
                "strategy-global-quality-copy", "release-42",
                ExecutionPlannerTestFixture.HASH_A, ExecutionPlannerTestFixture.HASH_B);
        TargetPositionPlanningRequest mismatched = fixture.request(
                fixture.snapshot("1", "10"), "2", wrongStrategy,
                new OrderPreference(ExecutionOrderType.MARKET,
                        ExecutionTimeInForce.DAY, null),
                ExecutionPlannerTestFixture.NOW.plusSeconds(30));

        assertThat(fixture.planner(fixture.acceptingVerifier()).plan(mismatched).code())
                .isEqualTo(ExecutionPlanningCode.BINDING_MISMATCH);
        assertThat(fixture.planner(fixture.acceptingVerifier())
                .plan(fixture.request("5", "-1")).code())
                .isEqualTo(ExecutionPlanningCode.POSITION_REVERSAL_REQUIRES_SEPARATE_TARGETS);
    }

    @Test
    void rejectsCapabilityBoundToAnotherRequestInstrumentOrSession() {
        TargetPositionPlanningRequest request = fixture.request("0", "2");
        VenueExecutionCapabilityVerifier wrongRequest = capability -> fixture.accepted(
                capability, TradingSessionStatus.OPEN, true, true,
                ExecutionPlannerTestFixture.HASH_A, capability.orderPreference().orderType(),
                capability.orderPreference().timeInForce(), capability.instrumentKey(),
                fixture.specification.sha256(), new BigDecimal("0.01"), null, null);
        InstrumentKey other = new InstrumentKey(
                new AssetClass("EQUITY"), "FIGI", "BBG000B9XRY5");
        VenueExecutionCapabilityVerifier wrongInstrument = capability -> fixture.accepted(
                capability, TradingSessionStatus.OPEN, true, true, capability.sha256(),
                capability.orderPreference().orderType(), capability.orderPreference().timeInForce(),
                other, fixture.specification.sha256(), new BigDecimal("0.01"), null, null);
        VenueExecutionCapabilityVerifier closed = capability -> fixture.accepted(
                capability, TradingSessionStatus.CLOSED, true, true, capability.sha256(),
                capability.orderPreference().orderType(), capability.orderPreference().timeInForce(),
                capability.instrumentKey(), fixture.specification.sha256(),
                new BigDecimal("0.01"), null, null);

        assertThat(fixture.planner(wrongRequest).plan(request).code())
                .isEqualTo(ExecutionPlanningCode.CAPABILITY_MISMATCH);
        assertThat(fixture.planner(wrongInstrument).plan(request).code())
                .isEqualTo(ExecutionPlanningCode.CAPABILITY_MISMATCH);
        assertThat(fixture.planner(closed).plan(request).code())
                .isEqualTo(ExecutionPlanningCode.TRADING_SESSION_NOT_OPEN);
    }

    @Test
    void rejectsOrderTypeAndTimeInForceNotExplicitlyVerifiedByVenue() {
        TargetPositionPlanningRequest request = fixture.request("0", "2");
        VenueExecutionCapabilityVerifier wrongType = capability -> fixture.accepted(
                capability, TradingSessionStatus.OPEN, true, true, capability.sha256(),
                ExecutionOrderType.MARKET, capability.orderPreference().timeInForce(),
                capability.instrumentKey(), fixture.specification.sha256(),
                new BigDecimal("0.01"), null, null);
        VenueExecutionCapabilityVerifier wrongTimeInForce = capability -> fixture.accepted(
                capability, TradingSessionStatus.OPEN, true, true, capability.sha256(),
                capability.orderPreference().orderType(), ExecutionTimeInForce.FILL_OR_KILL,
                capability.instrumentKey(), fixture.specification.sha256(),
                new BigDecimal("0.01"), null, null);

        assertThat(fixture.planner(wrongType).plan(request).code())
                .isEqualTo(ExecutionPlanningCode.UNSUPPORTED_ORDER_TYPE);
        assertThat(fixture.planner(wrongTimeInForce).plan(request).code())
                .isEqualTo(ExecutionPlanningCode.UNSUPPORTED_TIME_IN_FORCE);
    }

    @Test
    void rejectsInvalidPositiveOnlyPriceBeforeCallingVenueAndVerifierFailureFailsClosed() {
        TargetPositionPlanningRequest negativePrice = fixture.request(
                fixture.snapshot("0", "-1"), "2", fixture.release,
                new OrderPreference(ExecutionOrderType.MARKET,
                        ExecutionTimeInForce.DAY, null),
                ExecutionPlannerTestFixture.NOW.plusSeconds(30));
        VenueExecutionCapabilityVerifier mustNotRun = request -> {
            throw new AssertionError("verifier must not run for an invalid price domain");
        };

        assertThat(fixture.planner(mustNotRun).plan(negativePrice).code())
                .isEqualTo(ExecutionPlanningCode.INVALID_PRICE_RULE);
        assertThat(fixture.planner(request -> {
            throw new IllegalStateException("adapter unavailable");
        }).plan(fixture.request("0", "2")).code())
                .isEqualTo(ExecutionPlanningCode.CAPABILITY_UNAVAILABLE);
    }

    @Test
    void independentlyEnforcesNormalizationAndCapitalLimits() {
        TargetPositionPlanningRequest request = fixture.request("0", "2.019");
        VenueExecutionCapabilityVerifier badRounding = capability -> fixture.accepted(
                capability, TradingSessionStatus.OPEN, true, true, capability.sha256(),
                capability.orderPreference().orderType(), capability.orderPreference().timeInForce(),
                capability.instrumentKey(), fixture.specification.sha256(),
                new BigDecimal("0.01"), new BigDecimal("2.02"), null);
        VenueExecutionCapabilityVerifier overCapital = capability -> fixture.accepted(
                capability, TradingSessionStatus.OPEN, true, true, capability.sha256(),
                capability.orderPreference().orderType(), capability.orderPreference().timeInForce(),
                capability.instrumentKey(), fixture.specification.sha256(),
                new BigDecimal("0.01"), null, new BigDecimal("500.000000000000000001"));

        assertThat(fixture.planner(badRounding).plan(request).code())
                .isEqualTo(ExecutionPlanningCode.INVALID_QUANTITY_RULE);
        assertThat(fixture.planner(overCapital).plan(request).code())
                .isEqualTo(ExecutionPlanningCode.SINGLE_ORDER_CAPITAL_EXCEEDED);
    }

    @Test
    void requiresExplicitShortAndReduceOnlyVenueCapabilities() {
        TargetPositionPlanningRequest openShort = fixture.request("0", "-2");
        VenueExecutionCapabilityVerifier noShort = capability -> fixture.accepted(
                capability, TradingSessionStatus.OPEN, false, true, capability.sha256(),
                capability.orderPreference().orderType(), capability.orderPreference().timeInForce(),
                capability.instrumentKey(), fixture.specification.sha256(),
                new BigDecimal("0.01"), null, null);
        TargetPositionPlanningRequest reduceLong = fixture.request("5", "2");
        VenueExecutionCapabilityVerifier noReduceOnly = capability -> fixture.accepted(
                capability, TradingSessionStatus.OPEN, true, false, capability.sha256(),
                capability.orderPreference().orderType(), capability.orderPreference().timeInForce(),
                capability.instrumentKey(), fixture.specification.sha256(),
                new BigDecimal("0.01"), null, null);

        assertThat(fixture.planner(noShort).plan(openShort).code())
                .isEqualTo(ExecutionPlanningCode.SHORT_POSITION_NOT_SUPPORTED);
        assertThat(fixture.planner(noReduceOnly).plan(reduceLong).code())
                .isEqualTo(ExecutionPlanningCode.REDUCE_ONLY_NOT_SUPPORTED);
    }

    @Test
    void noDeltaNeverProducesCandidateAndInvalidDecimalDomainIsRejected() {
        ExecutionPlan plan = fixture.planner(request -> {
            throw new AssertionError("verifier must not run");
        }).plan(fixture.request("7.00", "7"));

        assertThat(plan.code()).isEqualTo(ExecutionPlanningCode.NO_POSITION_DELTA);
        assertThat(plan.candidate()).isEmpty();
        assertThatThrownBy(() -> fixture.request("0", "1E-100000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact decimal domain");
    }

    @Test
    void refusesConfigurableCandidateTtlBeyondHardMaximum() {
        assertThatThrownBy(() -> new DeterministicExecutionPlanner(
                Clock.fixed(ExecutionPlannerTestFixture.NOW, java.time.ZoneOffset.UTC),
                fixture.acceptingVerifier(), Duration.ofSeconds(121)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
