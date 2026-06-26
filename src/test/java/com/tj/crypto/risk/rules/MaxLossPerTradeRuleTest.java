package com.tj.crypto.risk.rules;

import com.tj.crypto.backtest.portfolio.VirtualAccount;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderType;
import com.tj.crypto.risk.RiskCheckResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MaxLossPerTradeRuleTest {

    private MaxLossPerTradeRule rule;
    private Instrument btcUsdt;
    private VirtualAccount account;

    @BeforeEach
    void setUp() {
        rule = new MaxLossPerTradeRule();
        rule.setMaxLossPct(BigDecimal.valueOf(2)); // 2%
        btcUsdt = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
        account = new VirtualAccount(BigDecimal.valueOf(10000));
    }

    @Test
    @DisplayName("订单金额在限制内应通过")
    void shouldPassWhenWithinLimit() {
        // 0.01 BTC * $16000 = $160 < $200 (2% of 10000)
        Order order = Order.create(btcUsdt, OrderSide.LONG, OrderType.MARKET,
                BigDecimal.valueOf(0.01), BigDecimal.valueOf(16000), 1000L);

        RiskCheckResult result = rule.check(order, account);
        assertThat(result.isPassed()).isTrue();
    }

    @Test
    @DisplayName("订单金额超出限制应拒绝")
    void shouldRejectWhenExceedsLimit() {
        // 0.02 BTC * $16000 = $320 > $200 (2% of 10000)
        Order order = Order.create(btcUsdt, OrderSide.LONG, OrderType.MARKET,
                BigDecimal.valueOf(0.02), BigDecimal.valueOf(16000), 1000L);

        RiskCheckResult result = rule.check(order, account);
        assertThat(result.isPassed()).isFalse();
    }
}
