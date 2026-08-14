package com.tj.crypto.factor.analysis;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.factor.cache.BarCache;
import com.tj.crypto.factor.core.Factor;
import com.tj.crypto.factor.core.FactorCalculator;
import com.tj.crypto.factor.core.FactorRegistry;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.EventMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FactorAnalyzerTest {

    private static final Timeframe TF = Timeframe.H1;
    private static final Instrument BTC = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");

    private BarCache barCache;
    private FactorAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        barCache = mock(BarCache.class);
    }

    // ==================== 辅助方法 ====================

    private BarEvent bar(long timestamp, double close) {
        BigDecimal c = BigDecimal.valueOf(close);
        EventMetadata metadata = EventMetadata.of(Exchange.BINANCE, timestamp);
        return new BarEvent(BTC, metadata, TF,
                c, c, c, c, BigDecimal.TEN, BigDecimal.valueOf(100), true);
    }

    private Factor factor(BigDecimal value) {
        return Factor.of("TEST_FACTOR", value, System.currentTimeMillis());
    }

    private FactorRegistry buildRegistry(Factor... factorValues) {
        FactorCalculator calc = mock(FactorCalculator.class);
        when(calc.name()).thenReturn("TEST_FACTOR");
        when(calc.calculate(any(), any(), anyList())).thenReturn(factorValues[0],
                java.util.Arrays.copyOfRange(factorValues, 1, factorValues.length));
        return new FactorRegistry(List.of(calc));
    }

    // ==================== IC 测试 ====================

    @Nested
    @DisplayName("calculateIC()")
    class CalculateICTests {

        @Test
        @DisplayName("正相关因子应返回正 IC")
        void shouldReturnPositiveICForPositiveCorrelation() {
            List<BarEvent> bars = List.of(
                    bar(1000, 90), bar(2000, 92), bar(3000, 88), bar(4000, 95)
            );
            when(barCache.getBars(eq(BTC), eq(TF), anyInt())).thenReturn(bars);

            FactorRegistry registry = buildRegistry(
                    factor(BigDecimal.valueOf(1.0)),
                    factor(BigDecimal.valueOf(-0.5)),
                    factor(BigDecimal.valueOf(1.0))
            );
            FactorAnalyzer analyzerWithRegistry = new FactorAnalyzer(barCache, registry);

            double ic = analyzerWithRegistry.calculateIC("TEST_FACTOR", BTC, TF, 1);

            assertThat(ic).isPositive();
            assertThat(ic).isGreaterThan(0.5);
        }

        @Test
        @DisplayName("负相关因子应返回负 IC")
        void shouldReturnNegativeICForNegativeCorrelation() {
            // Bars: 90, 85, 95, 88
            // Returns: (85-90)/90=-0.056, (95-85)/85=0.118, (88-95)/95=-0.074 → neg, pos, neg
            // Factor: 1.0, -1.0, 1.0 → pos, neg, pos (opposite = negative correlation)
            List<BarEvent> bars = List.of(
                    bar(1000, 90), bar(2000, 85), bar(3000, 95), bar(4000, 88)
            );
            when(barCache.getBars(eq(BTC), eq(TF), anyInt())).thenReturn(bars);

            FactorRegistry registry = buildRegistry(
                    factor(BigDecimal.valueOf(1.0)),
                    factor(BigDecimal.valueOf(-1.0)),
                    factor(BigDecimal.valueOf(1.0))
            );
            FactorAnalyzer analyzerWithRegistry = new FactorAnalyzer(barCache, registry);

            double ic = analyzerWithRegistry.calculateIC("TEST_FACTOR", BTC, TF, 1);

            assertThat(ic).isNegative();
            assertThat(ic).isLessThan(-0.5);
        }

        @Test
        @DisplayName("无相关性应返回接近 0 的 IC")
        void shouldReturnNearZeroICForUncorrelatedFactor() {
            // Constant factor (zero variance) → Pearson returns 0.0
            List<BarEvent> bars = List.of(
                    bar(1000, 90), bar(2000, 91), bar(3000, 90), bar(4000, 91)
            );
            when(barCache.getBars(eq(BTC), eq(TF), anyInt())).thenReturn(bars);

            FactorRegistry registry = buildRegistry(
                    factor(BigDecimal.valueOf(1.0)),
                    factor(BigDecimal.valueOf(1.0)),
                    factor(BigDecimal.valueOf(1.0))
            );
            FactorAnalyzer analyzerWithRegistry = new FactorAnalyzer(barCache, registry);

            double ic = analyzerWithRegistry.calculateIC("TEST_FACTOR", BTC, TF, 1);

            assertThat(ic).isEqualTo(0.0);
        }

        @Test
        @DisplayName("bar 数据不足应返回 NaN")
        void shouldReturnNaNWhenInsufficientBars() {
            when(barCache.getBars(eq(BTC), eq(TF), anyInt())).thenReturn(List.of(bar(1000, 90)));

            analyzer = new FactorAnalyzer(barCache, mock(FactorRegistry.class));
            double ic = analyzer.calculateIC("TEST_FACTOR", BTC, TF, 1);

            assertThat(ic).isNaN();
        }
    }

    // ==================== 命中率测试 ====================

    @Nested
    @DisplayName("calculateHitRate()")
    class CalculateHitRateTests {

        @Test
        @DisplayName("因子方向全部正确应返回 1.0")
        void shouldReturnOneForPerfectHitRate() {
            List<BarEvent> bars = List.of(
                    bar(1000, 90), bar(2000, 95), bar(3000, 88), bar(4000, 92)
            );
            when(barCache.getBars(eq(BTC), eq(TF), anyInt())).thenReturn(bars);

            FactorRegistry registry = buildRegistry(
                    factor(BigDecimal.valueOf(1.0)),
                    factor(BigDecimal.valueOf(-1.0)),
                    factor(BigDecimal.valueOf(1.0))
            );
            FactorAnalyzer analyzerWithRegistry = new FactorAnalyzer(barCache, registry);

            double hitRate = analyzerWithRegistry.calculateHitRate("TEST_FACTOR", BTC, TF, 1, 0.0);

            assertThat(hitRate).isEqualTo(1.0);
        }

        @Test
        @DisplayName("因子方向全部错误应返回 0.0")
        void shouldReturnZeroForZeroHitRate() {
            List<BarEvent> bars = List.of(
                    bar(1000, 90), bar(2000, 95), bar(3000, 88), bar(4000, 92)
            );
            when(barCache.getBars(eq(BTC), eq(TF), anyInt())).thenReturn(bars);

            FactorRegistry registry = buildRegistry(
                    factor(BigDecimal.valueOf(-1.0)),
                    factor(BigDecimal.valueOf(1.0)),
                    factor(BigDecimal.valueOf(-1.0))
            );
            FactorAnalyzer analyzerWithRegistry = new FactorAnalyzer(barCache, registry);

            double hitRate = analyzerWithRegistry.calculateHitRate("TEST_FACTOR", BTC, TF, 1, 0.0);

            assertThat(hitRate).isEqualTo(0.0);
        }

        @Test
        @DisplayName("bar 数据不足应返回 NaN")
        void shouldReturnNaNWhenInsufficientBars() {
            when(barCache.getBars(eq(BTC), eq(TF), anyInt())).thenReturn(List.of(bar(1000, 90)));

            analyzer = new FactorAnalyzer(barCache, mock(FactorRegistry.class));
            double hitRate = analyzer.calculateHitRate("TEST_FACTOR", BTC, TF, 1, 0.0);

            assertThat(hitRate).isNaN();
        }
    }

    // ==================== 因子收益统计测试 ====================

    @Nested
    @DisplayName("calculateFactorReturns()")
    class CalculateFactorReturnsTests {

        @Test
        @DisplayName("应正确分组并计算 t 检验")
        void shouldCorrectlyGroupAndComputeTTest() {
            List<BarEvent> bars = List.of(
                    bar(1000, 90), bar(2000, 95), bar(3000, 92),
                    bar(4000, 88), bar(5000, 95)
            );
            when(barCache.getBars(eq(BTC), eq(TF), anyInt())).thenReturn(bars);

            FactorRegistry registry = buildRegistry(
                    factor(BigDecimal.valueOf(1.0)),
                    factor(BigDecimal.valueOf(-1.0)),
                    factor(BigDecimal.valueOf(1.0)),
                    factor(BigDecimal.valueOf(1.0))
            );
            FactorAnalyzer analyzerWithRegistry = new FactorAnalyzer(barCache, registry);

            FactorReturnStats stats = analyzerWithRegistry.calculateFactorReturns("TEST_FACTOR", BTC, TF, 1);

            assertThat(stats.factorName()).isEqualTo("TEST_FACTOR");
            assertThat(stats.avgReturnWhenPositive()).isNotNull();
            assertThat(stats.avgReturnWhenNegative()).isNotNull();
            assertThat(stats.tStat()).isPositive();
            assertThat(stats.pValue()).isBetween(0.0, 1.0);
        }

        @Test
        @DisplayName("只有正因子组时 tStat 应为 0")
        void shouldReturnZeroTStatWhenOnlyOneGroup() {
            List<BarEvent> bars = List.of(
                    bar(1000, 90), bar(2000, 95), bar(3000, 92), bar(4000, 96)
            );
            when(barCache.getBars(eq(BTC), eq(TF), anyInt())).thenReturn(bars);

            FactorRegistry registry = buildRegistry(
                    factor(BigDecimal.valueOf(1.0)),
                    factor(BigDecimal.valueOf(0.5)),
                    factor(BigDecimal.valueOf(0.1))
            );
            FactorAnalyzer analyzerWithRegistry = new FactorAnalyzer(barCache, registry);

            FactorReturnStats stats = analyzerWithRegistry.calculateFactorReturns("TEST_FACTOR", BTC, TF, 1);

            assertThat(stats.avgReturnWhenPositive()).isNotNull();
            assertThat(stats.avgReturnWhenNegative()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(stats.tStat()).isEqualTo(0.0);
            assertThat(stats.pValue()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("bar 数据不足应返回零值统计")
        void shouldReturnZeroStatsWhenInsufficientBars() {
            when(barCache.getBars(eq(BTC), eq(TF), anyInt())).thenReturn(List.of(bar(1000, 90)));

            analyzer = new FactorAnalyzer(barCache, mock(FactorRegistry.class));
            FactorReturnStats stats = analyzer.calculateFactorReturns("TEST_FACTOR", BTC, TF, 1);

            assertThat(stats.factorName()).isEqualTo("TEST_FACTOR");
            assertThat(stats.avgReturnWhenPositive()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(stats.avgReturnWhenNegative()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(stats.tStat()).isEqualTo(0.0);
            assertThat(stats.pValue()).isEqualTo(1.0);
        }
    }
}
