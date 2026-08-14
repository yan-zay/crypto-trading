package com.tj.crypto.risk;

import com.tj.crypto.backtest.portfolio.TradingAccount;
import com.tj.crypto.backtest.portfolio.VirtualAccount;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderRejectReason;
import com.tj.crypto.execution.model.OrderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RiskEngineTest {

    private Instrument btcUsdt;
    private VirtualAccount account;

    @BeforeEach
    void setUp() {
        btcUsdt = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
        account = new VirtualAccount(BigDecimal.valueOf(10000));
    }

    @Test
    @DisplayName("所有规则通过时应返回 passed")
    void shouldPassWhenAllRulesPass() {
        RiskRule passRule = new RiskRule() {
            public String name() { return "PassRule"; }
            public RiskCheckResult check(Order order, TradingAccount acc) { return RiskCheckResult.passed(); }
        };

        RiskEngine engine = new RiskEngine(List.of(passRule));
        Order order = Order.create(btcUsdt, OrderSide.LONG, OrderType.MARKET,
                BigDecimal.ONE, BigDecimal.valueOf(16000), 1000L);

        RiskCheckResult result = engine.checkAll(order, account);
        assertThat(result.isPassed()).isTrue();
    }

    @Test
    @DisplayName("任一规则不通过时应返回 rejected")
    void shouldRejectWhenAnyRuleFails() {
        RiskRule passRule = new RiskRule() {
            public String name() { return "PassRule"; }
            public RiskCheckResult check(Order order, TradingAccount acc) { return RiskCheckResult.passed(); }
        };
        RiskRule failRule = new RiskRule() {
            public String name() { return "FailRule"; }
            public RiskCheckResult check(Order order, TradingAccount acc) {
                return RiskCheckResult.rejected(OrderRejectReason.RISK_REJECTED, "test rejection");
            }
        };

        RiskEngine engine = new RiskEngine(List.of(passRule, failRule));
        Order order = Order.create(btcUsdt, OrderSide.LONG, OrderType.MARKET,
                BigDecimal.ONE, BigDecimal.valueOf(16000), 1000L);

        RiskCheckResult result = engine.checkAll(order, account);
        assertThat(result.isPassed()).isFalse();
        assertThat(result.rejectReason()).isEqualTo(OrderRejectReason.RISK_REJECTED);
    }

    @Test
    @DisplayName("规则异常时应返回拒绝")
    void shouldRejectWhenRuleThrows() {
        RiskRule errorRule = new RiskRule() {
            public String name() { return "ErrorRule"; }
            public RiskCheckResult check(Order order, TradingAccount acc) { throw new RuntimeException("boom"); }
        };

        RiskEngine engine = new RiskEngine(List.of(errorRule));
        Order order = Order.create(btcUsdt, OrderSide.LONG, OrderType.MARKET,
                BigDecimal.ONE, BigDecimal.valueOf(16000), 1000L);

        RiskCheckResult result = engine.checkAll(order, account);
        assertThat(result.isPassed()).isFalse();
    }
}
