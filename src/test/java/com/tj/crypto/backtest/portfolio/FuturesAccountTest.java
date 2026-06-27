package com.tj.crypto.backtest.portfolio;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.OrderSide;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FuturesAccountTest {

    private FuturesAccount account;
    private Instrument btcUsdt;
    private Instrument ethUsdt;

    @BeforeEach
    void setUp() {
        account = new FuturesAccount(BigDecimal.valueOf(10000));
        btcUsdt = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
        ethUsdt = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "ETHUSDT");
    }

    // ========== 开仓 ==========

    @Nested
    @DisplayName("开仓")
    class OpenPosition {

        @Test
        @DisplayName("10x 杠杆多仓：保证金 = 名义价值 / 杠杆")
        void shouldOpenLongWithLeverage() {
            // 0.1 BTC @ $50000, 10x -> 名义价值 5000, 保证金 500
            boolean result = account.openPosition(btcUsdt, OrderSide.LONG,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000),
                    10, MarginMode.ISOLATED);

            assertThat(result).isTrue();
            assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(10000));
            assertThat(account.getAvailableBalance()).isEqualByComparingTo(BigDecimal.valueOf(9500));
            assertThat(account.getTotalMargin()).isEqualByComparingTo(BigDecimal.valueOf(500));
            assertThat(account.hasPosition(btcUsdt)).isTrue();
        }

        @Test
        @DisplayName("5x 杠杆空仓")
        void shouldOpenShortWithLeverage() {
            // 0.1 BTC @ $50000, 5x -> 名义价值 5000, 保证金 1000
            boolean result = account.openPosition(btcUsdt, OrderSide.SHORT,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000),
                    5, MarginMode.ISOLATED);

            assertThat(result).isTrue();
            assertThat(account.getAvailableBalance()).isEqualByComparingTo(BigDecimal.valueOf(9000));
            assertThat(account.getTotalMargin()).isEqualByComparingTo(BigDecimal.valueOf(1000));
        }

        @Test
        @DisplayName("余额不足应拒绝开仓")
        void shouldRejectWhenInsufficientBalance() {
            // 1 BTC @ $50000, 10x -> 保证金 5000. 余额仅 10000, 但再开第二个就超了
            account.openPosition(btcUsdt, OrderSide.LONG,
                    BigDecimal.valueOf(1), BigDecimal.valueOf(50000),
                    10, MarginMode.ISOLATED);

            // 第二个仓位需要 5000 保证金，但可用余额仅 5000
            // 再开一个 1 BTC 的，需要 5000 保证金，刚好够
            boolean result = account.openPosition(ethUsdt, OrderSide.LONG,
                    BigDecimal.valueOf(1), BigDecimal.valueOf(50000),
                    10, MarginMode.ISOLATED);

            assertThat(result).isTrue();
            assertThat(account.getAvailableBalance()).isEqualByComparingTo(BigDecimal.ZERO);

            // 第三个应该失败
            Instrument sol = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "SOLUSDT");
            result = account.openPosition(sol, OrderSide.LONG,
                    BigDecimal.valueOf(1), BigDecimal.valueOf(100),
                    10, MarginMode.ISOLATED);
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("重复持仓应拒绝")
        void shouldRejectDuplicatePosition() {
            account.openPosition(btcUsdt, OrderSide.LONG,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000),
                    10, MarginMode.ISOLATED);

            boolean result = account.openPosition(btcUsdt, OrderSide.SHORT,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000),
                    10, MarginMode.ISOLATED);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("杠杆 < 1 应抛异常")
        void shouldRejectInvalidLeverage() {
            assertThatThrownBy(() ->
                    account.openPosition(btcUsdt, OrderSide.LONG,
                            BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000),
                            0, MarginMode.ISOLATED)
            ).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("1x 杠杆等价于现货（保证金 = 名义价值）")
        void shouldRequireFullMarginAtOneX() {
            boolean result = account.openPosition(btcUsdt, OrderSide.LONG,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000),
                    1, MarginMode.ISOLATED);

            assertThat(result).isTrue();
            assertThat(account.getTotalMargin()).isEqualByComparingTo(BigDecimal.valueOf(5000));
            assertThat(account.getAvailableBalance()).isEqualByComparingTo(BigDecimal.valueOf(5000));
        }
    }

    // ========== 平仓 ==========

    @Nested
    @DisplayName("平仓")
    class ClosePosition {

        @Test
        @DisplayName("多仓盈利平仓")
        void shouldCloseLongWithProfit() {
            // 开多: 0.1 BTC @ $50000, 10x, margin = 500
            account.openPosition(btcUsdt, OrderSide.LONG,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000),
                    10, MarginMode.ISOLATED);

            // 平仓 @ $55000 -> PnL = 0.1 * (55000 - 50000) = 500
            Trade trade = account.closePosition(btcUsdt, BigDecimal.valueOf(55000));

            assertThat(trade).isNotNull();
            assertThat(trade.realizedPnL()).isEqualByComparingTo(BigDecimal.valueOf(500));
            // 余额 = 10000 + 500 (盈利) = 10500
            assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(10500));
            assertThat(account.hasPosition(btcUsdt)).isFalse();
        }

        @Test
        @DisplayName("多仓亏损平仓")
        void shouldCloseLongWithLoss() {
            account.openPosition(btcUsdt, OrderSide.LONG,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000),
                    10, MarginMode.ISOLATED);

            // 平仓 @ $47000 -> PnL = 0.1 * (47000 - 50000) = -300
            Trade trade = account.closePosition(btcUsdt, BigDecimal.valueOf(47000));

            assertThat(trade.realizedPnL()).isEqualByComparingTo(BigDecimal.valueOf(-300));
            // 余额 = 10000 - 300 (亏损) = 9700
            assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(9700));
        }

        @Test
        @DisplayName("空仓盈利平仓")
        void shouldCloseShortWithProfit() {
            account.openPosition(btcUsdt, OrderSide.SHORT,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000),
                    10, MarginMode.ISOLATED);

            // 平仓 @ $45000 -> PnL = 0.1 * (50000 - 45000) = 500
            Trade trade = account.closePosition(btcUsdt, BigDecimal.valueOf(45000));

            assertThat(trade.realizedPnL()).isEqualByComparingTo(BigDecimal.valueOf(500));
            // 余额 = 10000 + 500 (盈利) = 10500
            assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(10500));
        }

        @Test
        @DisplayName("空仓亏损平仓")
        void shouldCloseShortWithLoss() {
            account.openPosition(btcUsdt, OrderSide.SHORT,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000),
                    10, MarginMode.ISOLATED);

            // 平仓 @ $53000 -> PnL = 0.1 * (50000 - 53000) = -300
            Trade trade = account.closePosition(btcUsdt, BigDecimal.valueOf(53000));

            assertThat(trade.realizedPnL()).isEqualByComparingTo(BigDecimal.valueOf(-300));
            // 余额 = 10000 - 300 (亏损) = 9700
            assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(9700));
        }

        @Test
        @DisplayName("无持仓平仓返回 null")
        void shouldReturnNullWhenNoPosition() {
            Trade trade = account.closePosition(btcUsdt, BigDecimal.valueOf(50000));
            assertThat(trade).isNull();
        }

        @Test
        @DisplayName("平仓后交易记录应正确")
        void shouldRecordTradeAfterClose() {
            account.openPosition(btcUsdt, OrderSide.LONG,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000),
                    10, MarginMode.ISOLATED);
            account.closePosition(btcUsdt, BigDecimal.valueOf(55000));

            assertThat(account.getTrades()).hasSize(1);
            Trade trade = account.getTrades().get(0);
            assertThat(trade.instrument()).isEqualTo(btcUsdt);
            assertThat(trade.side()).isEqualTo(OrderSide.LONG);
            assertThat(trade.quantity()).isEqualByComparingTo(BigDecimal.valueOf(0.1));
            assertThat(trade.entryPrice()).isEqualByComparingTo(BigDecimal.valueOf(50000));
            assertThat(trade.exitPrice()).isEqualByComparingTo(BigDecimal.valueOf(55000));
        }
    }

    // ========== 杠杆效果 ==========

    @Nested
    @DisplayName("杠杆效果")
    class LeverageEffect {

        @Test
        @DisplayName("高杠杆放大盈亏")
        void shouldAmplifyPnLWithHigherLeverage() {
            // 10x: margin = 500, PnL = 500 -> ROI = 100%
            FuturesAccount account10x = new FuturesAccount(BigDecimal.valueOf(10000));
            account10x.openPosition(btcUsdt, OrderSide.LONG,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000),
                    10, MarginMode.ISOLATED);
            Trade trade10x = account10x.closePosition(btcUsdt, BigDecimal.valueOf(55000));

            // 20x: margin = 250, PnL = 500 -> ROI = 200%
            FuturesAccount account20x = new FuturesAccount(BigDecimal.valueOf(10000));
            account20x.openPosition(btcUsdt, OrderSide.LONG,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000),
                    20, MarginMode.ISOLATED);
            Trade trade20x = account20x.closePosition(btcUsdt, BigDecimal.valueOf(55000));

            // 盈亏金额相同（相同仓位大小）
            assertThat(trade10x.realizedPnL()).isEqualByComparingTo(trade20x.realizedPnL());
            // 但 20x 账户最终余额相同（因为仓位大小相同）
            // 10x: 10000 - 500 + 500 + 500 = 10500
            // 20x: 10000 - 250 + 250 + 500 = 10500
            assertThat(account10x.getBalance()).isEqualByComparingTo(account20x.getBalance());
        }

        @Test
        @DisplayName("相同资金高杠杆开更大仓位")
        void shouldOpenLargerPositionWithHigherLeverage() {
            // 10x: 10000 可开 100000 名义价值
            account.openPosition(btcUsdt, OrderSide.LONG,
                    BigDecimal.valueOf(2), BigDecimal.valueOf(50000),
                    10, MarginMode.ISOLATED);
            assertThat(account.getTotalMargin()).isEqualByComparingTo(BigDecimal.valueOf(10000));

            FuturesAccount account5x = new FuturesAccount(BigDecimal.valueOf(10000));
            // 5x: 10000 可开 50000 名义价值
            account5x.openPosition(ethUsdt, OrderSide.LONG,
                    BigDecimal.valueOf(1), BigDecimal.valueOf(50000),
                    5, MarginMode.ISOLATED);
            assertThat(account5x.getTotalMargin()).isEqualByComparingTo(BigDecimal.valueOf(10000));
        }
    }

    // ========== 强平价格 ==========

    @Nested
    @DisplayName("强平价格")
    class LiquidationPrice {

        @Test
        @DisplayName("多仓强平价格低于开仓价")
        void shouldCalculateLongLiquidationPriceBelowEntry() {
            // 0.1 BTC @ $50000, 10x, margin = 500
            // maintenanceMargin = 5000 * 0.005 = 25
            // liqPrice = 50000 - (500 - 25) / 0.1 = 50000 - 4750 = 45250
            account.openPosition(btcUsdt, OrderSide.LONG,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000),
                    10, MarginMode.ISOLATED);

            BigDecimal liqPrice = account.getLiquidationPrice(btcUsdt);
            assertThat(liqPrice).isEqualByComparingTo(BigDecimal.valueOf(45250));
        }

        @Test
        @DisplayName("空仓强平价格高于开仓价")
        void shouldCalculateShortLiquidationPriceAboveEntry() {
            // 0.1 BTC @ $50000, 10x, margin = 500
            // maintenanceMargin = 5000 * 0.005 = 25
            // liqPrice = 50000 + (500 - 25) / 0.1 = 50000 + 4750 = 54750
            account.openPosition(btcUsdt, OrderSide.SHORT,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000),
                    10, MarginMode.ISOLATED);

            BigDecimal liqPrice = account.getLiquidationPrice(btcUsdt);
            assertThat(liqPrice).isEqualByComparingTo(BigDecimal.valueOf(54750));
        }

        @Test
        @DisplayName("高杠杆强平价更接近开仓价")
        void shouldHaveCloserLiquidationPriceWithHigherLeverage() {
            FuturesAccount lowLev = new FuturesAccount(BigDecimal.valueOf(10000));
            lowLev.openPosition(btcUsdt, OrderSide.LONG,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000),
                    5, MarginMode.ISOLATED);

            FuturesAccount highLev = new FuturesAccount(BigDecimal.valueOf(10000));
            highLev.openPosition(btcUsdt, OrderSide.LONG,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000),
                    20, MarginMode.ISOLATED);

            BigDecimal liqLow = lowLev.getLiquidationPrice(btcUsdt);
            BigDecimal liqHigh = highLev.getLiquidationPrice(btcUsdt);

            // 高杠杆强平价更接近开仓价（多仓 = 更高）
            assertThat(liqHigh).isGreaterThan(liqLow);
            assertThat(liqHigh).isLessThan(BigDecimal.valueOf(50000));
        }

        @Test
        @DisplayName("无持仓返回 null")
        void shouldReturnNullForNonExistentPosition() {
            assertThat(account.getLiquidationPrice(btcUsdt)).isNull();
        }
    }

    // ========== 强制平仓 ==========

    @Nested
    @DisplayName("强制平仓")
    class Liquidation {

        @Test
        @DisplayName("强平多仓：亏损超过保证金")
        void shouldLiquidateLongPosition() {
            account.openPosition(btcUsdt, OrderSide.LONG,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000),
                    10, MarginMode.ISOLATED);

            // 价格跌到 44000, PnL = 0.1 * (44000 - 50000) = -600
            // margin = 500, margin + PnL = -100 -> 保证金耗尽
            Trade trade = account.liquidatePosition(btcUsdt, BigDecimal.valueOf(44000));

            assertThat(trade).isNotNull();
            assertThat(trade.realizedPnL()).isEqualByComparingTo(BigDecimal.valueOf(-600));
            // 保证金 500 退还 0（亏损 600 > 保证金 500）
            // 余额 = 10000 - 500 (保证金亏损) = 9500
            assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(9500));
            assertThat(account.hasPosition(btcUsdt)).isFalse();
        }

        @Test
        @DisplayName("强平空仓")
        void shouldLiquidateShortPosition() {
            account.openPosition(btcUsdt, OrderSide.SHORT,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000),
                    10, MarginMode.ISOLATED);

            // 价格涨到 56000, PnL = 0.1 * (50000 - 56000) = -600
            Trade trade = account.liquidatePosition(btcUsdt, BigDecimal.valueOf(56000));

            assertThat(trade).isNotNull();
            assertThat(trade.realizedPnL()).isEqualByComparingTo(BigDecimal.valueOf(-600));
            assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(9500));
        }

        @Test
        @DisplayName("强平后应记录交易")
        void shouldRecordTradeAfterLiquidation() {
            account.openPosition(btcUsdt, OrderSide.LONG,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000),
                    10, MarginMode.ISOLATED);
            account.liquidatePosition(btcUsdt, BigDecimal.valueOf(44000));

            assertThat(account.getTrades()).hasSize(1);
            assertThat(account.getTrades().get(0).realizedPnL())
                    .isEqualByComparingTo(BigDecimal.valueOf(-600));
        }
    }

    // ========== 资金费率 ==========

    @Nested
    @DisplayName("资金费率")
    class FundingRate {

        @Test
        @DisplayName("正费率：多头付费，空头收费")
        void shouldChargeLongOnPositiveFundingRate() {
            // 多头: 0.1 BTC @ $50000, 名义价值 5000
            account.openPosition(btcUsdt, OrderSide.LONG,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000),
                    10, MarginMode.ISOLATED);

            BigDecimal balanceBefore = account.getBalance();
            // 费率 0.01% = 0.0001 -> 资金费 = 5000 * 0.0001 = 0.5
            account.applyFundingRate(btcUsdt, BigDecimal.valueOf(0.0001));

            // 多头付费: 余额减少 0.5
            assertThat(account.getBalance())
                    .isEqualByComparingTo(balanceBefore.subtract(BigDecimal.valueOf(0.5)));
            assertThat(account.getTotalFundingPaid())
                    .isEqualByComparingTo(BigDecimal.valueOf(0.5));
        }

        @Test
        @DisplayName("正费率：空头收费")
        void shouldPayShortOnPositiveFundingRate() {
            account.openPosition(btcUsdt, OrderSide.SHORT,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000),
                    10, MarginMode.ISOLATED);

            BigDecimal balanceBefore = account.getBalance();
            account.applyFundingRate(btcUsdt, BigDecimal.valueOf(0.0001));

            // 空头收费: 余额增加 0.5
            assertThat(account.getBalance())
                    .isEqualByComparingTo(balanceBefore.add(BigDecimal.valueOf(0.5)));
        }

        @Test
        @DisplayName("负费率：空头付费，多头收费")
        void shouldChargeShortOnNegativeFundingRate() {
            account.openPosition(btcUsdt, OrderSide.SHORT,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000),
                    10, MarginMode.ISOLATED);

            BigDecimal balanceBefore = account.getBalance();
            // 负费率 -0.01% = -0.0001 -> 资金费 = 5000 * -0.0001 = -0.5
            account.applyFundingRate(btcUsdt, BigDecimal.valueOf(-0.0001));

            // 空头付费（负费率绝对值）: 余额减少 0.5
            assertThat(account.getBalance())
                    .isEqualByComparingTo(balanceBefore.subtract(BigDecimal.valueOf(0.5)));
        }

        @Test
        @DisplayName("无持仓时不结算资金费率")
        void shouldIgnoreFundingRateForNonExistentPosition() {
            BigDecimal balanceBefore = account.getBalance();
            account.applyFundingRate(btcUsdt, BigDecimal.valueOf(0.0001));
            assertThat(account.getBalance()).isEqualByComparingTo(balanceBefore);
        }
    }

    // ========== 未实现盈亏 & 总权益 ==========

    @Nested
    @DisplayName("未实现盈亏与总权益")
    class UnrealizedPnLAndEquity {

        @Test
        @DisplayName("多仓未实现盈亏")
        void shouldCalculateLongUnrealizedPnL() {
            account.openPosition(btcUsdt, OrderSide.LONG,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000),
                    10, MarginMode.ISOLATED);

            Map<String, BigDecimal> prices = Map.of("BTCUSDT", BigDecimal.valueOf(55000));
            BigDecimal upnl = account.getUnrealizedPnL(prices);
            // 0.1 * (55000 - 50000) = 500
            assertThat(upnl).isEqualByComparingTo(BigDecimal.valueOf(500));
        }

        @Test
        @DisplayName("空仓未实现盈亏")
        void shouldCalculateShortUnrealizedPnL() {
            account.openPosition(btcUsdt, OrderSide.SHORT,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000),
                    10, MarginMode.ISOLATED);

            Map<String, BigDecimal> prices = Map.of("BTCUSDT", BigDecimal.valueOf(45000));
            BigDecimal upnl = account.getUnrealizedPnL(prices);
            // 0.1 * (50000 - 45000) = 500
            assertThat(upnl).isEqualByComparingTo(BigDecimal.valueOf(500));
        }

        @Test
        @DisplayName("多仓位未实现盈亏合计")
        void shouldSumUnrealizedPnLAcrossPositions() {
            account.openPosition(btcUsdt, OrderSide.LONG,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000),
                    10, MarginMode.ISOLATED);
            account.openPosition(ethUsdt, OrderSide.SHORT,
                    BigDecimal.valueOf(1), BigDecimal.valueOf(3000),
                    10, MarginMode.ISOLATED);

            Map<String, BigDecimal> prices = Map.of(
                    "BTCUSDT", BigDecimal.valueOf(55000),
                    "ETHUSDT", BigDecimal.valueOf(2800)
            );
            BigDecimal upnl = account.getUnrealizedPnL(prices);
            // BTC: 0.1 * (55000 - 50000) = 500
            // ETH: 1 * (3000 - 2800) = 200
            assertThat(upnl).isEqualByComparingTo(BigDecimal.valueOf(700));
        }

        @Test
        @DisplayName("总权益 = 余额 + 未实现盈亏")
        void shouldCalculateTotalEquity() {
            account.openPosition(btcUsdt, OrderSide.LONG,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000),
                    10, MarginMode.ISOLATED);

            Map<String, BigDecimal> prices = Map.of("BTCUSDT", BigDecimal.valueOf(55000));
            BigDecimal equity = account.getTotalEquity(prices);
            // 余额 10000 + 未实现盈亏 500 = 10500
            assertThat(equity).isEqualByComparingTo(BigDecimal.valueOf(10500));
        }

        @Test
        @DisplayName("无持仓时总权益 = 余额")
        void shouldReturnBalanceWhenNoPositions() {
            Map<String, BigDecimal> prices = Map.of();
            BigDecimal equity = account.getTotalEquity(prices);
            assertThat(equity).isEqualByComparingTo(BigDecimal.valueOf(10000));
        }
    }

    // ========== 保证金比率 ==========

    @Nested
    @DisplayName("保证金比率")
    class MarginRatio {

        @Test
        @DisplayName("保证金比率应 < 1 表示安全")
        void shouldHaveMarginRatioBelowOneWhenSafe() {
            account.openPosition(btcUsdt, OrderSide.LONG,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000),
                    10, MarginMode.ISOLATED);

            Map<String, BigDecimal> prices = Map.of("BTCUSDT", BigDecimal.valueOf(50000));
            BigDecimal ratio = account.getMarginRatio(prices);
            // 维持保证金 = 5000 * 0.005 = 25
            // 总权益 = 10000 + 0 (未实现盈亏) = 10000
            // 比率 = 25 / 10000 = 0.0025
            assertThat(ratio).isEqualByComparingTo(BigDecimal.valueOf(0.0025));
        }

        @Test
        @DisplayName("亏损时保证金比率上升")
        void shouldIncreaseMarginRatioOnLoss() {
            account.openPosition(btcUsdt, OrderSide.LONG,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000),
                    10, MarginMode.ISOLATED);

            // 价格下跌 -> 总权益减少 -> 比率上升
            Map<String, BigDecimal> prices = Map.of("BTCUSDT", BigDecimal.valueOf(46000));
            BigDecimal ratio = account.getMarginRatio(prices);
            // PnL = 0.1 * (46000 - 50000) = -400
            // 总权益 = 10000 + (-400) = 9600
            // 比率 = 25 / 9600 = 0.00260416...
            assertThat(ratio).isGreaterThan(BigDecimal.valueOf(0.0025));
        }
    }

    // ========== 手续费 ==========

    @Nested
    @DisplayName("手续费")
    class Fees {

        @Test
        @DisplayName("开仓应扣除手续费")
        void shouldDeductOpenFee() {
            FeeModel feeModel = new MakerTakerFeeModel(
                    BigDecimal.valueOf(0.0002), BigDecimal.valueOf(0.0004));
            FuturesAccount accountWithFee = new FuturesAccount(BigDecimal.valueOf(10000), feeModel);

            // 0.1 BTC @ $50000, taker fee = 0.1 * 50000 * 0.0004 = 2
            accountWithFee.openPosition(btcUsdt, OrderSide.LONG,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000),
                    10, MarginMode.ISOLATED);

            // 余额 = 10000 - 2 (fee) = 9998（保证金不从 balance 扣除，通过 availableBalance 冻结）
            assertThat(accountWithFee.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(9998));
            assertThat(accountWithFee.getTotalFeesPaid()).isEqualByComparingTo(BigDecimal.valueOf(2));
        }

        @Test
        @DisplayName("平仓应扣除手续费")
        void shouldDeductCloseFee() {
            FeeModel feeModel = new MakerTakerFeeModel(
                    BigDecimal.valueOf(0.0002), BigDecimal.valueOf(0.0004));
            FuturesAccount accountWithFee = new FuturesAccount(BigDecimal.valueOf(10000), feeModel);

            accountWithFee.openPosition(btcUsdt, OrderSide.LONG,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000),
                    10, MarginMode.ISOLATED);

            // 平仓 @ $55000, close fee = 0.1 * 55000 * 0.0004 = 2.2
            Trade trade = accountWithFee.closePosition(btcUsdt, BigDecimal.valueOf(55000));

            assertThat(trade).isNotNull();
            // PnL = 500, close fee = 2.2
            // 余额 = 9998 (开仓后) + 500 (pnl) - 2.2 (close fee) = 10495.8
            assertThat(accountWithFee.getBalance())
                    .isEqualByComparingTo(BigDecimal.valueOf(10495.8));
            // 总手续费 = 2 (开仓) + 2.2 (平仓) = 4.2
            assertThat(accountWithFee.getTotalFeesPaid())
                    .isEqualByComparingTo(BigDecimal.valueOf(4.2));
        }
    }

    // ========== 多仓位管理 ==========

    @Nested
    @DisplayName("多仓位管理")
    class MultiplePositions {

        @Test
        @DisplayName("同时持有多仓和空仓")
        void shouldHoldBothLongAndShortPositions() {
            account.openPosition(btcUsdt, OrderSide.LONG,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000),
                    10, MarginMode.ISOLATED);
            account.openPosition(ethUsdt, OrderSide.SHORT,
                    BigDecimal.valueOf(1), BigDecimal.valueOf(3000),
                    10, MarginMode.ISOLATED);

            assertThat(account.getPositions()).hasSize(2);
            assertThat(account.hasPosition(btcUsdt)).isTrue();
            assertThat(account.hasPosition(ethUsdt)).isTrue();

            // 总保证金 = 500 (BTC) + 300 (ETH) = 800
            assertThat(account.getTotalMargin()).isEqualByComparingTo(BigDecimal.valueOf(800));
            // 可用余额 = 10000 - 800 = 9200
            assertThat(account.getAvailableBalance()).isEqualByComparingTo(BigDecimal.valueOf(9200));
        }

        @Test
        @DisplayName("平一个仓位不影响另一个")
        void shouldNotAffectOtherPositionsOnClose() {
            account.openPosition(btcUsdt, OrderSide.LONG,
                    BigDecimal.valueOf(0.1), BigDecimal.valueOf(50000),
                    10, MarginMode.ISOLATED);
            account.openPosition(ethUsdt, OrderSide.SHORT,
                    BigDecimal.valueOf(1), BigDecimal.valueOf(3000),
                    10, MarginMode.ISOLATED);

            account.closePosition(btcUsdt, BigDecimal.valueOf(55000));

            assertThat(account.getPositions()).hasSize(1);
            assertThat(account.hasPosition(ethUsdt)).isTrue();
        }
    }
}
