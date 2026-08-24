package com.tj.crypto.portfolio.analytics;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.InstrumentId;
import com.tj.crypto.common.domain.MarketType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapacityEstimatorTest {
    private static final InstrumentId BTC = new InstrumentId(Exchange.BINANCE,
            MarketType.PERPETUAL, "BTCUSDT");
    private final CapacityEstimator estimator = new CapacityEstimator();

    @Test
    void participationLimitBindsWhenImpactToleranceIsLoose() {
        CapacityEstimate estimate = estimator.estimate(request("1000", "100", "0.02",
                "0.01", "100", "1", "2", "0.5"));

        assertThat(estimate.averageDailyVolumeNotional()).isEqualByComparingTo("100000");
        assertThat(estimate.bindingParticipationRate()).isEqualByComparingTo("0.01");
        assertThat(estimate.tradeCapacityNotional()).isEqualByComparingTo("2000");
        assertThat(estimate.maximumCapitalAtTurnover()).isEqualByComparingTo("4000");
        assertThat(estimate.bindingConstraint()).isEqualTo(CapacityBindingConstraint.PARTICIPATION_LIMIT);
        assertThat(estimate.estimatedImpactBps()).isEqualByComparingTo("20");
    }

    @Test
    void impactLimitBindsAtSolvedSquareRootParticipation() {
        CapacityEstimate estimate = estimator.estimate(request("1000", "100", "0.02",
                "0.5", "10", "1", "1", "1"));

        assertThat(estimate.participationRateAllowedByImpact()).isEqualByComparingTo("0.0025");
        assertThat(estimate.bindingParticipationRate()).isEqualByComparingTo("0.0025");
        assertThat(estimate.tradeCapacityNotional()).isEqualByComparingTo("250");
        assertThat(estimate.estimatedImpactBps()).isEqualByComparingTo("10");
        assertThat(estimate.bindingConstraint()).isEqualTo(CapacityBindingConstraint.MARKET_IMPACT_LIMIT);
    }

    @Test
    void capacityIsMonotonicInVolumePriceAndHorizonForFixedConstraints() {
        CapacityEstimate previous = null;
        for (int multiplier = 1; multiplier <= 50; multiplier++) {
            CapacityEstimate current = estimator.estimate(request(
                    Integer.toString(1000 * multiplier), "100", "0.02",
                    "0.01", "100", "1", Integer.toString(multiplier), "0.5"));
            if (previous != null) {
                assertThat(current.tradeCapacityNotional())
                        .isGreaterThan(previous.tradeCapacityNotional());
                assertThat(current.maximumCapitalAtTurnover())
                        .isGreaterThan(previous.maximumCapitalAtTurnover());
            }
            previous = current;
        }
    }

    @Test
    void rejectsImpossibleOrNonPositiveCapacityInputs() {
        assertThatThrownBy(() -> request("0", "100", "0.02", "0.01",
                "100", "1", "1", "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("averageDailyVolume");
        assertThatThrownBy(() -> request("100", "100", "0.02", "1.01",
                "100", "1", "1", "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed 1");
    }

    @Test
    void preservesTinyImpactAndMakesTurnoverTimeBasisExplicit() {
        CapacityEstimate tiny = estimator.estimate(request("1000", "100", "1",
                "1", "0.0000000000001", "1", "1", "1"));
        assertThat(tiny.estimatedImpactBps()).isPositive();

        CapacityEstimate perDay = estimator.estimate(request("1000", "100", "0.02",
                "0.01", "100", "1", "2", "0.5", TurnoverRateBasis.PER_DAY));
        CapacityEstimate perHorizon = estimator.estimate(request("1000", "100", "0.02",
                "0.01", "100", "1", "2", "0.5", TurnoverRateBasis.EXECUTION_HORIZON));
        assertThat(perDay.maximumCapitalAtTurnover()).isEqualByComparingTo("2000");
        assertThat(perHorizon.maximumCapitalAtTurnover()).isEqualByComparingTo("4000");
    }

    private CapacityEstimateRequest request(String volume, String price, String volatility,
                                            String participation, String impactBps,
                                            String coefficient, String horizon, String turnover) {
        return request(volume, price, volatility, participation, impactBps, coefficient,
                horizon, turnover, TurnoverRateBasis.EXECUTION_HORIZON);
    }

    private CapacityEstimateRequest request(String volume, String price, String volatility,
                                            String participation, String impactBps,
                                            String coefficient, String horizon, String turnover,
                                            TurnoverRateBasis basis) {
        CapacityMarketDataSnapshot marketData = new CapacityMarketDataSnapshot(BTC, "USDT",
                ExposureValuationModel.LINEAR_QUOTE_SETTLED,
                CapacityVolumeUnit.BASE_ASSET_QUANTITY, bd(volume), BigDecimal.ONE, bd(price),
                1_000, 2_000, 2_000, 2_000, 0, "a".repeat(64));
        return new CapacityEstimateRequest(marketData, bd(volatility),
                bd(participation), bd(impactBps), bd(coefficient), bd(horizon), bd(turnover), basis);
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
