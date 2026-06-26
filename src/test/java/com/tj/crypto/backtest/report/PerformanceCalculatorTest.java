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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PerformanceCalculatorTest {

    private PerformanceCalculator calculator;
    private Instrument btcUsdt;

    @BeforeEach
    void setUp() {
        calculator = new PerformanceCalculator();
        btcUsdt = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
    }

    private Trade createTrade(OrderSide side, BigDecimal entry, BigDecimal exit, BigDecimal pnl) {
        return new Trade(btcUsdt, side, BigDecimal.ONE, entry, exit, 1000L, 2000L, pnl);
    }

    @Test
    @DisplayName("空交易列表应返回零指标")
    void shouldReturnZeroForEmptyTrades() {
        PerformanceReport report = calculator.calculate(
                List.of(), BigDecimal.valueOf(10000), BigDecimal.valueOf(10000), 0L, 100L);

        assertThat(report.totalTrades()).isEqualTo(0);
        assertThat(report.totalReturn()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("应正确计算总收益率")
    void shouldCalculateTotalReturn() {
        List<Trade> trades = List.of(
                createTrade(OrderSide.LONG, BigDecimal.valueOf(100), BigDecimal.valueOf(110), BigDecimal.valueOf(10)),
                createTrade(OrderSide.LONG, BigDecimal.valueOf(110), BigDecimal.valueOf(105), BigDecimal.valueOf(-5))
        );

        PerformanceReport report = calculator.calculate(
                trades, BigDecimal.valueOf(1000), BigDecimal.valueOf(1005), 0L, 100L);

        // 总收益 = (1005 - 1000) / 1000 * 100 = 0.5%
        assertThat(report.totalReturn()).isEqualByComparingTo(BigDecimal.valueOf(0.5));
    }

    @Test
    @DisplayName("应正确计算胜率")
    void shouldCalculateWinRate() {
        List<Trade> trades = List.of(
                createTrade(OrderSide.LONG, BigDecimal.valueOf(100), BigDecimal.valueOf(110), BigDecimal.valueOf(10)),
                createTrade(OrderSide.LONG, BigDecimal.valueOf(110), BigDecimal.valueOf(105), BigDecimal.valueOf(-5)),
                createTrade(OrderSide.LONG, BigDecimal.valueOf(105), BigDecimal.valueOf(115), BigDecimal.valueOf(10))
        );

        PerformanceReport report = calculator.calculate(
                trades, BigDecimal.valueOf(1000), BigDecimal.valueOf(1015), 0L, 100L);

        // 2/3 = 66.67%
        assertThat(report.winRate()).isGreaterThan(BigDecimal.valueOf(66));
        assertThat(report.winRate()).isLessThan(BigDecimal.valueOf(67));
    }

    @Test
    @DisplayName("应正确计算盈亏比")
    void shouldCalculateProfitFactor() {
        List<Trade> trades = List.of(
                createTrade(OrderSide.LONG, BigDecimal.valueOf(100), BigDecimal.valueOf(110), BigDecimal.valueOf(10)),
                createTrade(OrderSide.LONG, BigDecimal.valueOf(110), BigDecimal.valueOf(105), BigDecimal.valueOf(-5))
        );

        PerformanceReport report = calculator.calculate(
                trades, BigDecimal.valueOf(1000), BigDecimal.valueOf(1005), 0L, 100L);

        // PF = 10 / 5 = 2.0
        assertThat(report.profitFactor()).isEqualByComparingTo(BigDecimal.valueOf(2));
    }

    @Test
    @DisplayName("应正确统计交易次数")
    void shouldCountTrades() {
        List<Trade> trades = List.of(
                createTrade(OrderSide.LONG, BigDecimal.valueOf(100), BigDecimal.valueOf(110), BigDecimal.valueOf(10)),
                createTrade(OrderSide.LONG, BigDecimal.valueOf(110), BigDecimal.valueOf(105), BigDecimal.valueOf(-5))
        );

        PerformanceReport report = calculator.calculate(
                trades, BigDecimal.valueOf(1000), BigDecimal.valueOf(1005), 0L, 100L);

        assertThat(report.totalTrades()).isEqualTo(2);
        assertThat(report.winningTrades()).isEqualTo(1);
        assertThat(report.losingTrades()).isEqualTo(1);
    }
}
