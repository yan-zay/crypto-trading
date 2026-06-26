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
        long now = System.currentTimeMillis();
        account.openPosition(btcUsdt, OrderSide.LONG,
                BigDecimal.valueOf(0.1), BigDecimal.valueOf(16000), now - 3600_000);
        account.closePosition(btcUsdt, BigDecimal.valueOf(15000), now);

        Order order = Order.create(btcUsdt, OrderSide.LONG, OrderType.MARKET,
                BigDecimal.valueOf(0.01), BigDecimal.valueOf(16000), 1000L);

        RiskCheckResult result = rule.check(order, account);
        assertThat(result.isPassed()).isTrue();
    }

    @Test
    @DisplayName("当日亏损超限时应拒绝")
    void shouldRejectWhenDailyLossExceedsLimit() {
        // 亏损 $600 > $500 (5% of 10000)
        long now = System.currentTimeMillis();
        account.openPosition(btcUsdt, OrderSide.LONG,
                BigDecimal.valueOf(0.1), BigDecimal.valueOf(16000), now - 3600_000);
        account.closePosition(btcUsdt, BigDecimal.valueOf(10000), now);

        Order order = Order.create(btcUsdt, OrderSide.LONG, OrderType.MARKET,
                BigDecimal.valueOf(0.01), BigDecimal.valueOf(16000), 1000L);

        RiskCheckResult result = rule.check(order, account);
        assertThat(result.isPassed()).isFalse();
    }
}
