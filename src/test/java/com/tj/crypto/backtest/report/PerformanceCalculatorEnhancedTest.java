package com.tj.crypto.backtest.report;

import com.tj.crypto.backtest.portfolio.Trade;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.OrderSide;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PerformanceCalculatorEnhancedTest {

    private static final long MILLIS_PER_DAY = 86_400_000L;
    private static final long MILLIS_PER_YEAR = 365L * MILLIS_PER_DAY;

    private PerformanceCalculator calculator;
    private Instrument btcUsdt;

    @BeforeEach
    void setUp() {
        calculator = new PerformanceCalculator();
        btcUsdt = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
    }

    private Trade trade(OrderSide side, BigDecimal entry, BigDecimal exit, BigDecimal qty,
                        BigDecimal pnl, long entryTime, long exitTime) {
        return new Trade(btcUsdt, side, qty, entry, exit, entryTime, exitTime, pnl);
    }

    /**
     * 构造一组跨越 1 年的 4 笔交易：
     * 3 胜 1 亏，总收益 +25，初始 10000。
     */
    private List<Trade> oneYearTrades() {
        return List.of(
                trade(OrderSide.LONG, bd(100), bd(110), bd(1), bd(10),
                        0, 91 * MILLIS_PER_DAY),
                trade(OrderSide.LONG, bd(110), bd(105), bd(1), bd(-5),
                        91 * MILLIS_PER_DAY, 182 * MILLIS_PER_DAY),
                trade(OrderSide.LONG, bd(105), bd(115), bd(1), bd(10),
                        182 * MILLIS_PER_DAY, 273 * MILLIS_PER_DAY),
                trade(OrderSide.LONG, bd(115), bd(125), bd(1), bd(10),
                        273 * MILLIS_PER_DAY, MILLIS_PER_YEAR)
        );
    }

    @Test
    @DisplayName("空交易列表应返回零指标（含新增字段）")
    void emptyTradesShouldReturnZeroForAllNewFields() {
        PerformanceReport report = calculator.calculate(
                List.of(), bd(10000), bd(10000), 0L, MILLIS_PER_YEAR);

        assertThat(report.annualizedReturn()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.sharpeRatio()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.sortinoRatio()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.calmarRatio()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.avgTradeDuration()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.maxWinStreak()).isEqualTo(0);
        assertThat(report.maxLoseStreak()).isEqualTo(0);
        assertThat(report.monthlyReturns()).isEmpty();
    }

    @Test
    @DisplayName("应正确计算年化收益率")
    void shouldCalculateAnnualizedReturn() {
        List<Trade> trades = oneYearTrades();
        // 总收益 = 25 / 10000 * 100 = 0.25%
        // 跨越 1 年 → 年化 ≈ 0.25%
        PerformanceReport report = calculator.calculate(
                trades, bd(10000), bd(10025), 0L, MILLIS_PER_YEAR);

        assertThat(report.totalReturn()).isEqualByComparingTo(bd(0.25));
        assertThat(report.annualizedReturn()).isCloseTo(bd(0.25), org.assertj.core.data.Offset.offset(bd(0.01)));
    }

    @Test
    @DisplayName("年化收益率应正确缩放非1年期收益")
    void annualizedReturnShouldScaleForShorterPeriod() {
        // 182.5 天内获得 0.25% 收益 → 年化应大于 0.25%
        long halfYear = MILLIS_PER_YEAR / 2;
        List<Trade> trades = List.of(
                trade(OrderSide.LONG, bd(100), bd(110), bd(1), bd(10), 0, halfYear / 2),
                trade(OrderSide.LONG, bd(110), bd(115), bd(1), bd(15), halfYear / 2, halfYear)
        );

        PerformanceReport report = calculator.calculate(
                trades, bd(10000), bd(10025), 0L, halfYear);

        // annReturn = (1.0025^2 - 1) * 100 ≈ 0.5006%
        assertThat(report.annualizedReturn()).isGreaterThan(bd(0.4));
        assertThat(report.annualizedReturn()).isLessThan(bd(0.6));
    }

    @Test
    @DisplayName("应正确计算夏普比率")
    void shouldCalculateSharpeRatio() {
        PerformanceReport report = calculator.calculate(
                oneYearTrades(), bd(10000), bd(10025), 0L, MILLIS_PER_YEAR);

        // Sharpe 不为零且为有限值
        assertThat(report.sharpeRatio()).isNotEqualTo(BigDecimal.ZERO);
        // 所有交易收益 > 0 的偏差，mean > riskFreePerTrade 时 Sharpe 为正
        // 但这里 mean per-trade return ~0.0005，riskFreePerTrade ~0.005，所以 Sharpe 为负
        assertThat(report.sharpeRatio().doubleValue()).isFinite();
    }

    @Test
    @DisplayName("应正确计算索提诺比率")
    void shouldCalculateSortinoRatio() {
        PerformanceReport report = calculator.calculate(
                oneYearTrades(), bd(10000), bd(10025), 0L, MILLIS_PER_YEAR);

        assertThat(report.sortinoRatio()).isNotEqualTo(BigDecimal.ZERO);
        assertThat(report.sortinoRatio().doubleValue()).isFinite();
    }

    @Test
    @DisplayName("所有交易收益率相同时索提诺比率应为零（无下行偏差）")
    void sortinoShouldBeZeroWhenAllReturnsEqual() {
        // 4 笔全部盈利，且每笔收益率相同（PnL/notional 都 = 0.1）
        List<Trade> trades = List.of(
                trade(OrderSide.LONG, bd(100), bd(110), bd(1), bd(10), 0, 91 * MILLIS_PER_DAY),
                trade(OrderSide.LONG, bd(100), bd(110), bd(1), bd(10), 91 * MILLIS_PER_DAY, 182 * MILLIS_PER_DAY),
                trade(OrderSide.LONG, bd(100), bd(110), bd(1), bd(10), 182 * MILLIS_PER_DAY, 273 * MILLIS_PER_DAY),
                trade(OrderSide.LONG, bd(100), bd(110), bd(1), bd(10), 273 * MILLIS_PER_DAY, MILLIS_PER_YEAR)
        );

        PerformanceReport report = calculator.calculate(
                trades, bd(10000), bd(10040), 0L, MILLIS_PER_YEAR);

        // 每笔交易收益率相同 → 全等于 mean → 无下行偏差 → Sortino 为 0（downsideStd=0）
        assertThat(report.sortinoRatio()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("应正确计算卡玛比率")
    void shouldCalculateCalmarRatio() {
        PerformanceReport report = calculator.calculate(
                oneYearTrades(), bd(10000), bd(10025), 0L, MILLIS_PER_YEAR);

        // Calmar = annualizedReturn / maxDrawdown
        if (report.maxDrawdown().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal expected = report.annualizedReturn()
                    .divide(report.maxDrawdown(), 6, RoundingMode.HALF_UP);
            assertThat(report.calmarRatio()).isEqualByComparingTo(expected);
        } else {
            assertThat(report.calmarRatio()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Test
    @DisplayName("应正确计算连胜和连亏次数")
    void shouldCalculateWinAndLoseStreaks() {
        // 胜 胜 胜 亏 亏 胜 亏 亏 亏
        List<Trade> trades = List.of(
                trade(OrderSide.LONG, bd(100), bd(110), bd(1), bd(10), 0, 1),
                trade(OrderSide.LONG, bd(100), bd(110), bd(1), bd(10), 1, 2),
                trade(OrderSide.LONG, bd(100), bd(110), bd(1), bd(10), 2, 3),
                trade(OrderSide.LONG, bd(100), bd(90), bd(1), bd(-10), 3, 4),
                trade(OrderSide.LONG, bd(100), bd(90), bd(1), bd(-10), 4, 5),
                trade(OrderSide.LONG, bd(100), bd(110), bd(1), bd(10), 5, 6),
                trade(OrderSide.LONG, bd(100), bd(90), bd(1), bd(-10), 6, 7),
                trade(OrderSide.LONG, bd(100), bd(90), bd(1), bd(-10), 7, 8),
                trade(OrderSide.LONG, bd(100), bd(90), bd(1), bd(-10), 8, 9)
        );

        PerformanceReport report = calculator.calculate(
                trades, bd(10000), bd(9970), 0L, 10L);

        assertThat(report.maxWinStreak()).isEqualTo(3);
        assertThat(report.maxLoseStreak()).isEqualTo(3);
        // maxConsecutiveLosses 也是 3
        assertThat(report.maxConsecutiveLosses()).isEqualTo(3);
    }

    @Test
    @DisplayName("应正确计算平均交易时长")
    void shouldCalculateAvgTradeDuration() {
        // 3 笔交易，时长分别为 1000, 2000, 3000 ms
        List<Trade> trades = List.of(
                trade(OrderSide.LONG, bd(100), bd(110), bd(1), bd(10), 0, 1000),
                trade(OrderSide.LONG, bd(100), bd(110), bd(1), bd(10), 1000, 3000),
                trade(OrderSide.LONG, bd(100), bd(110), bd(1), bd(10), 3000, 6000)
        );

        PerformanceReport report = calculator.calculate(
                trades, bd(10000), bd(10030), 0L, 6000);

        // avg = (1000 + 2000 + 3000) / 3 = 2000
        assertThat(report.avgTradeDuration()).isEqualByComparingTo(bd(2000));
    }

    @Test
    @DisplayName("应正确计算月度收益")
    void shouldCalculateMonthlyReturns() {
        // 2024-01-15 和 2024-02-20 各平仓一笔
        long jan15 = 1705276800000L;  // 2024-01-15 00:00:00 UTC
        long feb20 = 1708387200000L;  // 2024-02-20 00:00:00 UTC

        List<Trade> trades = List.of(
                trade(OrderSide.LONG, bd(100), bd(110), bd(1), bd(10), jan15 - 1000, jan15),
                trade(OrderSide.LONG, bd(100), bd(90), bd(1), bd(-5), feb20 - 1000, feb20)
        );

        PerformanceReport report = calculator.calculate(
                trades, bd(10000), bd(10005), jan15 - 1000, feb20);

        Map<String, BigDecimal> monthly = report.monthlyReturns();
        assertThat(monthly).containsKey("2024-01");
        assertThat(monthly).containsKey("2024-02");
        assertThat(monthly.get("2024-01")).isEqualByComparingTo(bd(10));
        assertThat(monthly.get("2024-02")).isEqualByComparingTo(bd(-5));
    }

    @Test
    @DisplayName("连续亏损时卡玛比率应为负")
    void calmarShouldBeNegativeWhenLosing() {
        List<Trade> trades = List.of(
                trade(OrderSide.LONG, bd(100), bd(90), bd(1), bd(-10), 0, MILLIS_PER_YEAR / 2),
                trade(OrderSide.LONG, bd(90), bd(80), bd(1), bd(-10), MILLIS_PER_YEAR / 2, MILLIS_PER_YEAR)
        );

        PerformanceReport report = calculator.calculate(
                trades, bd(10000), bd(9980), 0L, MILLIS_PER_YEAR);

        // 负收益 / 正 maxDrawdown → 负 Calmar
        assertThat(report.calmarRatio().doubleValue()).isLessThan(0);
    }

    @Test
    @DisplayName("toString 应包含新增指标")
    void toStringShouldIncludeNewMetrics() {
        PerformanceReport report = calculator.calculate(
                oneYearTrades(), bd(10000), bd(10025), 0L, MILLIS_PER_YEAR);

        String str = report.toString();
        assertThat(str).contains("annReturn");
        assertThat(str).contains("Sharpe");
        assertThat(str).contains("Sortino");
        assertThat(str).contains("Calmar");
    }

    private BigDecimal bd(long value) {
        return BigDecimal.valueOf(value);
    }

    private BigDecimal bd(double value) {
        return BigDecimal.valueOf(value);
    }
}
