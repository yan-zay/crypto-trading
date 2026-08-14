package com.tj.crypto.strategy.factor;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.factor.core.Factor;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.EventMetadata;
import com.tj.crypto.strategy.core.SignalEvent;
import com.tj.crypto.strategy.core.SignalType;
import com.tj.crypto.strategy.core.StrategyContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeFactorStrategyTest {

    private final Instrument instrument = Instrument.of(
            Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
    private final MutableContext context = new MutableContext();

    @Test
    void combinesTwoEntryFactorsAndOneExitFactor() {
        CompositeFactorStrategy strategy = new CompositeFactorStrategy(new FactorStrategySpec(
                "RSI_MACD",
                FactorPositionMode.LONG_ONLY,
                group(FactorMatchMode.ALL,
                        constant("RSI", FactorOperator.LTE, "30"),
                        constant("MACD_HIST", FactorOperator.GTE, "0")),
                group(FactorMatchMode.ANY,
                        constant("RSI", FactorOperator.GTE, "70")),
                null,
                null));

        context.values.put("RSI", new BigDecimal("25"));
        context.values.put("MACD_HIST", new BigDecimal("0.2"));
        SignalEvent entry = strategy.onEvent(bar(1L, "100"), context);
        assertThat(entry.type()).isEqualTo(SignalType.BUY);
        assertThat(entry.factorSnapshot()).containsEntry("RSI", new BigDecimal("25"));

        assertThat(strategy.onEvent(bar(2L, "101"), context)).isNull();
        context.values.put("RSI", new BigDecimal("72"));
        SignalEvent exit = strategy.onEvent(bar(3L, "102"), context);
        assertThat(exit.type()).isEqualTo(SignalType.SELL);
    }

    @Test
    void supportsCrossingPriceAndWeightedRules() {
        FactorRule crossAbovePrice = new FactorRule(
                "SMA", FactorOperator.CROSS_ABOVE, FactorComparisonTarget.PRICE,
                null, null, BigDecimal.ONE);
        FactorRule rsi = new FactorRule(
                "RSI", FactorOperator.LT, FactorComparisonTarget.CONSTANT,
                new BigDecimal("40"), null, BigDecimal.ONE);
        FactorRuleGroup entry = new FactorRuleGroup(
                FactorMatchMode.WEIGHTED, new BigDecimal("0.5"), List.of(crossAbovePrice, rsi));
        CompositeFactorStrategy strategy = new CompositeFactorStrategy(new FactorStrategySpec(
                "WEIGHTED", FactorPositionMode.LONG_ONLY, entry,
                group(FactorMatchMode.ALL, constant("RSI", FactorOperator.GT, "80")),
                null, null));

        context.values.put("SMA", new BigDecimal("99"));
        context.values.put("RSI", new BigDecimal("50"));
        assertThat(strategy.onEvent(bar(1L, "100"), context)).isNull();
        context.values.put("SMA", new BigDecimal("102"));
        SignalEvent signal = strategy.onEvent(bar(2L, "101"), context);

        assertThat(signal).isNotNull();
        assertThat(signal.confidence()).isEqualByComparingTo("0.5");
    }

    private FactorRuleGroup group(FactorMatchMode mode, FactorRule... rules) {
        return new FactorRuleGroup(mode, BigDecimal.ONE, List.of(rules));
    }

    private FactorRule constant(String name, FactorOperator operator, String threshold) {
        return new FactorRule(name, operator, FactorComparisonTarget.CONSTANT,
                new BigDecimal(threshold), null, BigDecimal.ONE);
    }

    private BarEvent bar(long timestamp, String close) {
        BigDecimal value = new BigDecimal(close);
        return new BarEvent(instrument,
                new EventMetadata(Exchange.BINANCE, timestamp, timestamp, null),
                Timeframe.M1, value, value, value, value,
                BigDecimal.ONE, value, true);
    }

    private static final class MutableContext implements StrategyContext {
        private final Map<String, BigDecimal> values = new HashMap<>();

        @Override
        public Factor getFactor(String name, Instrument instrument, Timeframe timeframe) {
            BigDecimal value = values.get(name);
            return value == null ? null : Factor.of(name, value, 1L);
        }

        @Override
        public List<Factor> getAllFactors(Instrument instrument, Timeframe timeframe) {
            return values.entrySet().stream()
                    .map(entry -> Factor.of(entry.getKey(), entry.getValue(), 1L))
                    .toList();
        }
    }
}
