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

class FeeModelTest {

    private MakerTakerFeeModel feeModel;
    private Instrument btcUsdt;

    @BeforeEach
    void setUp() {
        // 使用默认费率：maker 0.02%, taker 0.04%
        FeeProperties props = new FeeProperties();
        feeModel = new MakerTakerFeeModel(props);
        btcUsdt = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
    }

    @Test
    @DisplayName("默认 Taker 手续费率应为 0.04%")
    void shouldHaveDefaultTakerFeeRate() {
        MakerTakerFeeModel defaultModel = new MakerTakerFeeModel(new FeeProperties());
        assertThat(defaultModel.getTakerFeeRate())
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("0.0004"));
    }

    @Test
    @DisplayName("默认 Maker 手续费率应为 0.02%")
    void shouldHaveDefaultMakerFeeRate() {
        MakerTakerFeeModel defaultModel = new MakerTakerFeeModel(new FeeProperties());
        assertThat(defaultModel.getMakerFeeRate())
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("0.0002"));
    }

    @Test
    @DisplayName("应正确计算 Taker 手续费")
    void shouldCalculateTakerFee() {
        // 0.1 BTC @ $50000, taker fee = 0.1 * 50000 * 0.0004 = 2.0
        BigDecimal fee = feeModel.calculateFee(
                OrderSide.LONG,
                BigDecimal.valueOf(0.1),
                BigDecimal.valueOf(50000)
        );

        assertThat(fee).isEqualByComparingTo(BigDecimal.valueOf(2.0));
    }

    @Test
    @DisplayName("应正确计算 Maker 手续费")
    void shouldCalculateMakerFee() {
        // 0.1 BTC @ $50000, maker fee = 0.1 * 50000 * 0.0002 = 1.0
        BigDecimal fee = feeModel.calculateMakerFee(
                OrderSide.LONG,
                BigDecimal.valueOf(0.1),
                BigDecimal.valueOf(50000)
        );

        assertThat(fee).isEqualByComparingTo(BigDecimal.valueOf(1.0));
    }

    @Test
    @DisplayName("Taker 手续费应大于 Maker 手续费")
    void takerFeeShouldBeGreaterThanMakerFee() {
        BigDecimal takerFee = feeModel.calculateTakerFee(
                OrderSide.LONG,
                BigDecimal.valueOf(1),
                BigDecimal.valueOf(10000)
        );
        BigDecimal makerFee = feeModel.calculateMakerFee(
                OrderSide.LONG,
                BigDecimal.valueOf(1),
                BigDecimal.valueOf(10000)
        );

        assertThat(takerFee).isGreaterThan(makerFee);
    }

    @Test
    @DisplayName("空头方向应计算相同手续费")
    void shouldCalculateSameFeeForShort() {
        BigDecimal longFee = feeModel.calculateFee(
                OrderSide.LONG,
                BigDecimal.valueOf(0.5),
                BigDecimal.valueOf(20000)
        );
        BigDecimal shortFee = feeModel.calculateFee(
                OrderSide.SHORT,
                BigDecimal.valueOf(0.5),
                BigDecimal.valueOf(20000)
        );

        assertThat(longFee).isEqualByComparingTo(shortFee);
    }

    @Test
    @DisplayName("零数量应返回零手续费")
    void shouldReturnZeroFeeForZeroQuantity() {
        BigDecimal fee = feeModel.calculateFee(
                OrderSide.LONG,
                BigDecimal.ZERO,
                BigDecimal.valueOf(50000)
        );

        assertThat(fee).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("零价格应返回零手续费")
    void shouldReturnZeroFeeForZeroPrice() {
        BigDecimal fee = feeModel.calculateFee(
                OrderSide.LONG,
                BigDecimal.valueOf(1),
                BigDecimal.ZERO
        );

        assertThat(fee).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("应正确计算大额交易手续费")
    void shouldCalculateFeeForLargeTrade() {
        // 10 BTC @ $60000, taker fee = 10 * 60000 * 0.0004 = 240
        BigDecimal fee = feeModel.calculateFee(
                OrderSide.LONG,
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(60000)
        );

        assertThat(fee).isEqualByComparingTo(BigDecimal.valueOf(240));
    }

    @Test
    @DisplayName("VirtualAccount 应正确扣除开仓手续费")
    void shouldDeductOpenFee() {
        VirtualAccount account = new VirtualAccount(BigDecimal.valueOf(10000), feeModel);

        // 开仓：0.1 BTC @ $50000，成本=5000，手续费=0.1*50000*0.0004=2.0
        boolean result = account.openPosition(btcUsdt, OrderSide.LONG,
                BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000), 1000L);

        assertThat(result).isTrue();
        // 余额 = 10000 - 5000 - 2.0 = 4998.0
        assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(4998));
        assertThat(account.getTotalFeesPaid()).isEqualByComparingTo(BigDecimal.valueOf(2.0));
    }

    @Test
    @DisplayName("VirtualAccount 应正确扣除平仓手续费")
    void shouldDeductCloseFee() {
        VirtualAccount account = new VirtualAccount(BigDecimal.valueOf(10000), feeModel);

        // 开仓：0.1 BTC @ $50000，手续费=2.0
        account.openPosition(btcUsdt, OrderSide.LONG,
                BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000), 1000L);

        // 平仓：@ $55000，手续费=0.1*55000*0.0004=2.2
        Trade trade = account.closePosition(btcUsdt, BigDecimal.valueOf(55000), 2000L);

        assertThat(trade).isNotNull();
        // PnL = 0.1 * (55000 - 50000) = 500
        assertThat(trade.realizedPnL()).isEqualByComparingTo(BigDecimal.valueOf(500));
        // 总手续费 = 开仓 2.0 + 平仓 2.2 = 4.2
        assertThat(trade.totalFee()).isEqualByComparingTo(BigDecimal.valueOf(4.2));
        // 总手续费已支付 = 2.0 + 2.2 = 4.2
        assertThat(account.getTotalFeesPaid()).isEqualByComparingTo(BigDecimal.valueOf(4.2));
        // 余额 = 4998 (开仓后) + 5000(退还成本) + 500(盈利) - 2.2(平仓手续费) = 10495.8
        assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(10495.8));
    }

    @Test
    @DisplayName("余额不足（含手续费）时应拒绝开仓")
    void shouldRejectOpenWhenInsufficientBalanceIncludingFee() {
        VirtualAccount account = new VirtualAccount(BigDecimal.valueOf(5001), feeModel);

        // 开仓：0.1 BTC @ $50000，成本=5000，手续费=2.0，总需=5002
        boolean result = account.openPosition(btcUsdt, OrderSide.LONG,
                BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000), 1000L);

        assertThat(result).isFalse();
        assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(5001));
    }
}
