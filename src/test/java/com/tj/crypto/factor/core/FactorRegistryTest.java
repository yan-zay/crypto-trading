package com.tj.crypto.factor.core;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FactorRegistryTest {

    private Instrument btcUsdt;
    private FactorRegistry registry;

    @BeforeEach
    void setUp() {
        btcUsdt = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
    }

    private FactorCalculator mockCalculator(String name, Factor result) {
        FactorCalculator calc = mock(FactorCalculator.class);
        when(calc.name()).thenReturn(name);
        when(calc.calculate(any(), any())).thenReturn(result);
        return calc;
    }

    @Test
    @DisplayName("calculate() 应返回正确因子值")
    void shouldReturnCorrectFactorValue() {
        Factor expected = Factor.of("SMA", BigDecimal.valueOf(42000), System.currentTimeMillis());
        FactorCalculator smaCalc = mockCalculator("SMA", expected);
        registry = new FactorRegistry(List.of(smaCalc));

        Factor result = registry.calculate("SMA", btcUsdt, Timeframe.M1);

        assertThat(result).isNotNull();
        assertThat(result.name()).isEqualTo("SMA");
        assertThat(result.value()).isEqualByComparingTo(BigDecimal.valueOf(42000));
    }

    @Test
    @DisplayName("calculate() 未知因子应返回 null")
    void shouldReturnNullForUnknownFactor() {
        FactorCalculator smaCalc = mockCalculator("SMA", Factor.of("SMA", BigDecimal.ONE, 0));
        registry = new FactorRegistry(List.of(smaCalc));

        Factor result = registry.calculate("UNKNOWN_FACTOR", btcUsdt, Timeframe.M1);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("calculateAll() 应返回所有可用因子")
    void shouldReturnAllUsableFactors() {
        Factor smaFactor = Factor.of("SMA", BigDecimal.valueOf(42000), System.currentTimeMillis());
        Factor rsiFactor = Factor.of("RSI", BigDecimal.valueOf(65), System.currentTimeMillis());
        FactorCalculator smaCalc = mockCalculator("SMA", smaFactor);
        FactorCalculator rsiCalc = mockCalculator("RSI", rsiFactor);
        registry = new FactorRegistry(List.of(smaCalc, rsiCalc));

        List<Factor> results = registry.calculateAll(btcUsdt, Timeframe.M1);

        assertThat(results).hasSize(2);
        assertThat(results).extracting(Factor::name).containsExactlyInAnyOrder("SMA", "RSI");
    }

    @Test
    @DisplayName("calculateAll() 应过滤掉 WARMUP 因子")
    void shouldFilterOutWarmupFactors() {
        Factor readyFactor = Factor.of("SMA", BigDecimal.valueOf(42000), System.currentTimeMillis());
        Factor warmupFactor = Factor.warmup("RSI");
        FactorCalculator smaCalc = mockCalculator("SMA", readyFactor);
        FactorCalculator rsiCalc = mockCalculator("RSI", warmupFactor);
        registry = new FactorRegistry(List.of(smaCalc, rsiCalc));

        List<Factor> results = registry.calculateAll(btcUsdt, Timeframe.M1);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).name()).isEqualTo("SMA");
    }

    @Test
    @DisplayName("单个因子计算失败不应影响其他因子")
    void shouldNotAffectOtherFactorsWhenOneCalculationFails() {
        Factor goodFactor = Factor.of("SMA", BigDecimal.valueOf(42000), System.currentTimeMillis());
        FactorCalculator goodCalc = mockCalculator("SMA", goodFactor);

        FactorCalculator badCalc = mock(FactorCalculator.class);
        when(badCalc.name()).thenReturn("RSI");
        when(badCalc.calculate(any(), any())).thenThrow(new RuntimeException("calculation error"));

        registry = new FactorRegistry(List.of(goodCalc, badCalc));

        List<Factor> results = registry.calculateAll(btcUsdt, Timeframe.M1);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).name()).isEqualTo("SMA");
    }

    @Test
    @DisplayName("getRegisteredFactors() 应返回所有注册名称")
    void shouldReturnAllRegisteredFactorNames() {
        FactorCalculator smaCalc = mockCalculator("SMA", Factor.of("SMA", BigDecimal.ONE, 0));
        FactorCalculator rsiCalc = mockCalculator("RSI", Factor.of("RSI", BigDecimal.ONE, 0));
        FactorCalculator macdCalc = mockCalculator("MACD_HIST", Factor.of("MACD_HIST", BigDecimal.ONE, 0));
        registry = new FactorRegistry(List.of(smaCalc, rsiCalc, macdCalc));

        List<String> names = registry.getRegisteredFactors();

        assertThat(names).hasSize(3);
        assertThat(names).containsExactlyInAnyOrder("SMA", "RSI", "MACD_HIST");
    }
}
