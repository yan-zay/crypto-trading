package com.tj.crypto.risk.rules;

import com.tj.crypto.backtest.portfolio.VirtualAccount;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.common.domain.TradeSide;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyBudgetRuleTest {

    @Test
    void isolatedSessionKeepsConfigurationButResetsAccountingState() {
        StrategyBudgetRule original = new StrategyBudgetRule(BigDecimal.valueOf(50));
        Instrument instrument = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
        VirtualAccount account = new VirtualAccount(BigDecimal.valueOf(10_000));
        Order largeFill = Order.create("strategy-a", instrument, TradeSide.BUY,
                OrderSide.LONG, OrderSide.LONG, false, OrderType.MARKET,
                BigDecimal.valueOf(60), BigDecimal.valueOf(100), 1L);
        original.onOrderFilled(largeFill);

        Order nextOrder = Order.create("strategy-a", instrument, TradeSide.BUY,
                OrderSide.LONG, OrderSide.LONG, false, OrderType.MARKET,
                BigDecimal.ONE, BigDecimal.valueOf(100), 2L);
        StrategyBudgetRule isolated = original.newSession();

        assertThat(original.check(nextOrder, account).isPassed()).isFalse();
        assertThat(isolated.check(nextOrder, account).isPassed()).isTrue();
        assertThat(isolated.getMaxStrategyBudgetPct()).isEqualByComparingTo("50");
    }
}
