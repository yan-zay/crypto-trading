package com.tj.crypto.risk.rules;

import com.tj.crypto.backtest.portfolio.VirtualAccount;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderType;
import com.tj.crypto.risk.RiskCheckResult;
import com.tj.crypto.risk.RiskProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MaxDailyLossRuleTest {

    private MaxDailyLossRule rule;
    private Instrument btcUsdt;
    private VirtualAccount account;

    @BeforeEach
    void setUp() {
        RiskProperties riskProperties = new RiskProperties();
        riskProperties.setMaxDailyLossPct(BigDecimal.valueOf(5)); // 5%
        rule = new MaxDailyLossRule(riskProperties);
        btcUsdt = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
        account = new VirtualAccount(BigDecimal.valueOf(10000));
    }

    @Test
    @DisplayName("无交易记录时应通过")
    void shouldPassWhenNoTrades() {
        Order order = Order.create(btcUsdt, OrderSide.LONG, OrderType.MARKET,
                BigDecimal.valueOf(0.01), BigDecimal.valueOf(16000), 1000L);

        RiskCheckResult result = rule.check(order, account);
        assertThat(result.isPassed()).isTrue();
    }

    @Test
    @DisplayName("当日亏损未超限时应通过")
    void shouldPassWhenDailyLossWithinLimit() {
        // 亏损 $100 < $500 (5% of 10000)
        long now = 100_000L;
        account.openPosition(btcUsdt, OrderSide.LONG,
                BigDecimal.valueOf(0.1), BigDecimal.valueOf(16000), now - 3600_000);
        account.closePosition(btcUsdt, BigDecimal.valueOf(15000), now);

        // 订单时间在平仓之后，确保交易在 24h 窗口内
        Order order = Order.create(btcUsdt, OrderSide.LONG, OrderType.MARKET,
                BigDecimal.valueOf(0.01), BigDecimal.valueOf(16000), now + 1);

        RiskCheckResult result = rule.check(order, account);
        assertThat(result.isPassed()).isTrue();
    }

    @Test
    @DisplayName("当日亏损超限时应拒绝")
    void shouldRejectWhenDailyLossExceedsLimit() {
        // 亏损 $600 > $500 (5% of 10000)
        long now = 100_000L;
        account.openPosition(btcUsdt, OrderSide.LONG,
                BigDecimal.valueOf(0.1), BigDecimal.valueOf(16000), now - 3600_000);
        account.closePosition(btcUsdt, BigDecimal.valueOf(10000), now);

        // 订单时间在平仓之后，确保交易在 24h 窗口内
        Order order = Order.create(btcUsdt, OrderSide.LONG, OrderType.MARKET,
                BigDecimal.valueOf(0.01), BigDecimal.valueOf(16000), now + 1);

        RiskCheckResult result = rule.check(order, account);
        assertThat(result.isPassed()).isFalse();
    }

    @Test
    @DisplayName("回测场景：使用历史时间戳而非系统时间")
    void shouldUseOrderTimestampForBacktest() {
        // 回测时间：2024-01-01 00:00:00 UTC = 1704067200000L
        long backtestNow = 1704067200000L;
        long oneHourBefore = backtestNow - 3600_000;

        account.openPosition(btcUsdt, OrderSide.LONG,
                BigDecimal.valueOf(0.1), BigDecimal.valueOf(16000), oneHourBefore);
        account.closePosition(btcUsdt, BigDecimal.valueOf(10000), backtestNow);

        // 订单时间在回测时间点
        Order order = Order.create(btcUsdt, OrderSide.LONG, OrderType.MARKET,
                BigDecimal.valueOf(0.01), BigDecimal.valueOf(16000), backtestNow + 1);

        RiskCheckResult result = rule.check(order, account);
        // 亏损 $600 > $500，应拒绝
        assertThat(result.isPassed()).isFalse();
    }

    @Test
    @DisplayName("超过 24h 窗口的交易不计入当日亏损")
    void shouldExcludeOldTradesFromDailyLoss() {
        long now = 100_000L;
        // 亏损发生在 25 小时前，超出 24h 窗口
        long oldTime = now - 25 * 60 * 60 * 1000;
        account.openPosition(btcUsdt, OrderSide.LONG,
                BigDecimal.valueOf(0.1), BigDecimal.valueOf(16000), oldTime - 3600_000);
        account.closePosition(btcUsdt, BigDecimal.valueOf(10000), oldTime);

        Order order = Order.create(btcUsdt, OrderSide.LONG, OrderType.MARKET,
                BigDecimal.valueOf(0.01), BigDecimal.valueOf(16000), now);

        RiskCheckResult result = rule.check(order, account);
        // 旧交易不在窗口内，应通过
        assertThat(result.isPassed()).isTrue();
    }
}
