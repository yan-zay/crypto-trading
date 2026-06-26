package com.tj.crypto.backtest.portfolio;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.OrderSide;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class VirtualAccountTest {

    private VirtualAccount account;
    private Instrument btcUsdt;

    @BeforeEach
    void setUp() {
        account = new VirtualAccount(BigDecimal.valueOf(10000));
        btcUsdt = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
    }

    @Test
    @DisplayName("开仓应冻结资金")
    void shouldFreezeBalanceOnOpen() {
        boolean result = account.openPosition(btcUsdt, OrderSide.LONG,
                BigDecimal.valueOf(0.1), BigDecimal.valueOf(16000), 1000L);

        assertThat(result).isTrue();
        // 10000 - 0.1 * 16000 = 10000 - 1600 = 8400
        assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(8400));
        assertThat(account.hasPosition(btcUsdt)).isTrue();
    }

    @Test
    @DisplayName("余额不足时应拒绝开仓")
    void shouldRejectOpenWhenInsufficientBalance() {
        boolean result = account.openPosition(btcUsdt, OrderSide.LONG,
                BigDecimal.valueOf(1), BigDecimal.valueOf(16000), 1000L);

        assertThat(result).isFalse();
        assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(10000));
    }

    @Test
    @DisplayName("平仓应释放资金并计算盈亏")
    void shouldReleaseBalanceAndCalculatePnLOnClose() {
        // 开多仓：0.1 BTC @ $16000，冻结 1600
        account.openPosition(btcUsdt, OrderSide.LONG,
                BigDecimal.valueOf(0.1), BigDecimal.valueOf(16000), 1000L);

        // 平仓：@ $17000，盈利 = 0.1 * (17000 - 16000) = 100
        Trade trade = account.closePosition(btcUsdt, BigDecimal.valueOf(17000), 2000L);

        assertThat(trade).isNotNull();
        assertThat(trade.realizedPnL()).isEqualByComparingTo(BigDecimal.valueOf(100));
        // 8400 (开仓后余额) + 0.1 * 17000 (平仓释放) = 8400 + 1700 = 10100
        assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(10100));
        assertThat(account.getTrades()).hasSize(1);
    }

    @Test
    @DisplayName("空仓平仓应正确计算盈亏")
    void shouldCalculateShortPnL() {
        // 开空仓：0.1 BTC @ $16000
        account.openPosition(btcUsdt, OrderSide.SHORT,
                BigDecimal.valueOf(0.1), BigDecimal.valueOf(16000), 1000L);

        // 平仓：@ $15000，盈利 = 0.1 * (16000 - 15000) = 100
        Trade trade = account.closePosition(btcUsdt, BigDecimal.valueOf(15000), 2000L);

        assertThat(trade.realizedPnL()).isEqualByComparingTo(BigDecimal.valueOf(100));
    }

    @Test
    @DisplayName("无持仓时平仓应返回 null")
    void shouldReturnNullWhenNoPosition() {
        Trade trade = account.closePosition(btcUsdt, BigDecimal.valueOf(16000), 1000L);
        assertThat(trade).isNull();
    }
}
