package com.tj.crypto.execution;

import com.tj.crypto.backtest.portfolio.TradingAccount;
import com.tj.crypto.backtest.portfolio.VirtualAccount;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderRejectReason;
import com.tj.crypto.execution.model.OrderStatus;
import com.tj.crypto.execution.journal.ExecutionJournal;
import com.tj.crypto.execution.journal.ExecutionJournalException;
import com.tj.crypto.risk.KillSwitch;
import com.tj.crypto.risk.RiskCheckResult;
import com.tj.crypto.risk.RiskEngine;
import com.tj.crypto.risk.RiskProperties;
import com.tj.crypto.risk.RiskRule;
import com.tj.crypto.risk.PositionSizer;
import com.tj.crypto.strategy.core.SignalEvent;
import com.tj.crypto.strategy.core.SignalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExecutionEngine 单元测试。
 *
 * 使用真实的 RiskEngine（带 mock rules）、PositionSizer、FixedSlippageModel。
 * VirtualAccount 初始余额 10000。
 */
class ExecutionEngineTest {

    private static final BigDecimal INITIAL_BALANCE = BigDecimal.valueOf(10000);
    private static final BigDecimal BTC_PRICE = BigDecimal.valueOf(50000);
    private static final long TIMESTAMP = 1700000000000L;

    private static final Instrument BTC_USDT = new Instrument(
            Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT", "BTC", "USDT"
    );

    private ExecutionEngine engine;
    private VirtualAccount account;
    private RiskEngine riskEngine;
    private RiskProperties riskProperties;
    private FixedSlippageModel slippageModel;
    private KillSwitch killSwitch;

    /** 始终通过的风控规则 */
    private RiskRule alwaysPassRule;

    @BeforeEach
    void setUp() {
        account = new VirtualAccount(INITIAL_BALANCE);
        riskProperties = new RiskProperties();
        slippageModel = new FixedSlippageModel(riskProperties);
        killSwitch = new KillSwitch();

        alwaysPassRule = new RiskRule() {
            @Override
            public String name() {
                return "AlwaysPass";
            }

            @Override
            public RiskCheckResult check(Order order, TradingAccount account) {
                return RiskCheckResult.passed();
            }
        };

        riskEngine = new RiskEngine(List.of(alwaysPassRule));
        engine = new ExecutionEngine(riskEngine, new PositionSizer(riskProperties), slippageModel, killSwitch);
    }

    // ─── 辅助方法 ───────────────────────────────────────────────

    private SignalEvent signal(SignalType type, BigDecimal confidence) {
        return new SignalEvent(
                "TestStrategy", BTC_USDT, type, confidence,
                "test signal", Map.of(), TIMESTAMP
        );
    }

    // ─── 1. BUY 信号应开仓 ─────────────────────────────────────

    @Nested
    @DisplayName("BUY 信号")
    class BuySignal {

        @Test
        @DisplayName("BUY 信号产生 FILLED 订单并开仓")
        void shouldOpenPosition() {
            SignalEvent buySignal = signal(SignalType.BUY, BigDecimal.ONE);

            Order order = engine.execute(buySignal, account, BTC_PRICE, TIMESTAMP);

            assertNotNull(order, "BUY 信号应产生非 null 订单");
            assertEquals(OrderStatus.FILLED, order.status(), "订单状态应为 FILLED");
            assertEquals(OrderSide.LONG, order.side(), "订单方向应为 LONG");
            assertEquals(BTC_USDT, order.instrument(), "订单交易对应为 BTC_USDT");

            // 验证账户已开仓
            assertTrue(account.hasPosition(BTC_USDT), "账户应持有 BTC_USDT 仓位");
            assertTrue(account.getBalance().compareTo(INITIAL_BALANCE) < 0,
                    "开仓后余额应减少");
        }
    }

    // ─── 2. SELL 信号应平仓 ─────────────────────────────────────

    @Nested
    @DisplayName("SELL 信号")
    class SellSignal {

        @Test
        @DisplayName("SELL 信号平掉已有仓位，产生 FILLED 订单")
        void shouldCloseExistingPosition() {
            // 先开仓
            boolean opened = account.openPosition(
                    BTC_USDT, OrderSide.LONG,
                    BigDecimal.valueOf(0.1), BTC_PRICE, TIMESTAMP
            );
            assertTrue(opened, "预置开仓应成功");
            assertTrue(account.hasPosition(BTC_USDT), "开仓后应持有仓位");

            // 发出 SELL 信号
            SignalEvent sellSignal = signal(SignalType.SELL, BigDecimal.ONE);
            Order order = engine.execute(sellSignal, account, BTC_PRICE, TIMESTAMP + 1);

            assertNotNull(order, "SELL 信号应产生非 null 订单");
            assertEquals(OrderStatus.FILLED, order.status(), "订单状态应为 FILLED");
            assertEquals(OrderSide.SHORT, order.side(), "订单方向应为 SHORT");

            // 验证仓位已平掉
            assertFalse(account.hasPosition(BTC_USDT), "平仓后不应持有仓位");
        }
    }

    // ─── 3. HOLD 信号不产生订单 ─────────────────────────────────

    @Nested
    @DisplayName("HOLD 信号")
    class HoldSignal {

        @Test
        @DisplayName("HOLD 信号返回 null")
        void shouldReturnNull() {
            SignalEvent holdSignal = signal(SignalType.HOLD, BigDecimal.ONE);

            Order order = engine.execute(holdSignal, account, BTC_PRICE, TIMESTAMP);

            assertNull(order, "HOLD 信号应返回 null");
        }
    }

    // ─── 4. 风控拒绝时返回 REJECTED 订单 ────────────────────────

    @Nested
    @DisplayName("风控拒绝")
    class RiskRejection {

        @Test
        @DisplayName("风控规则拒绝时返回 REJECTED 订单")
        void shouldRejectWhenRiskRuleFails() {
            // 使用会被触发的风控规则：订单金额 > 余额 * 0.1%
            RiskRule strictRule = new RiskRule() {
                @Override
                public String name() {
                    return "StrictLimit";
                }

                @Override
                public RiskCheckResult check(Order order, TradingAccount acct) {
                    BigDecimal orderValue = order.quantity().multiply(order.price());
                    BigDecimal limit = acct.getBalance()
                            .multiply(BigDecimal.valueOf(0.1))
                            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                    if (orderValue.compareTo(limit) > 0) {
                        return RiskCheckResult.rejected(
                                OrderRejectReason.RISK_REJECTED,
                                "订单金额超过限制"
                        );
                    }
                    return RiskCheckResult.passed();
                }
            };

            RiskEngine strictRiskEngine = new RiskEngine(List.of(strictRule));
            ExecutionEngine strictEngine = new ExecutionEngine(
                    strictRiskEngine, new PositionSizer(riskProperties), slippageModel, killSwitch
            );

            SignalEvent buySignal = signal(SignalType.BUY, BigDecimal.ONE);
            Order order = strictEngine.execute(buySignal, account, BTC_PRICE, TIMESTAMP);

            assertNotNull(order, "被拒绝的信号应产生非 null 订单");
            assertEquals(OrderStatus.REJECTED, order.status(), "订单状态应为 REJECTED");
            assertEquals(OrderRejectReason.RISK_REJECTED, order.rejectReason(),
                    "拒绝原因应为 RISK_REJECTED");

            // 验证未开仓
            assertFalse(account.hasPosition(BTC_USDT), "被拒绝后不应持有仓位");
            assertEquals(INITIAL_BALANCE, account.getBalance(), "被拒绝后余额不变");
        }
    }

    // ─── 5. 余额不足时返回 REJECTED ─────────────────────────────

    @Nested
    @DisplayName("余额不足")
    class InsufficientBalance {

        @Test
        @DisplayName("余额为零时返回 REJECTED（INSUFFICIENT_BALANCE）")
        void shouldRejectWhenBalanceIsZero() {
            // 用掉全部余额开仓
            account.openPosition(
                    new Instrument(Exchange.BINANCE, MarketType.PERPETUAL,
                            "ETHUSDT", "ETH", "USDT"),
                    OrderSide.LONG, BigDecimal.valueOf(0.2), BigDecimal.valueOf(50000), TIMESTAMP
            );
            // 余额 = 10000 - 10000 = 0

            // 用一个不同交易对的信号来测试余额不足（避免触发平仓逻辑）
            Instrument solUsdt = new Instrument(Exchange.BINANCE, MarketType.PERPETUAL,
                    "SOLUSDT", "SOL", "USDT");
            SignalEvent buySignal = new SignalEvent(
                    "TestStrategy", solUsdt, SignalType.BUY, BigDecimal.ONE,
                    "test signal", Map.of(), TIMESTAMP
            );
            Order order = engine.execute(buySignal, account, BigDecimal.valueOf(50000), TIMESTAMP);

            assertNotNull(order, "余额为 0 时应产生非 null 订单");
            assertEquals(OrderStatus.REJECTED, order.status(), "余额为 0 时订单应被拒绝");
            assertEquals(OrderRejectReason.INSUFFICIENT_BALANCE, order.rejectReason(),
                    "拒绝原因应为 INSUFFICIENT_BALANCE");
        }
    }

    // ─── 6. 合约交易：位置感知执行 ──────────────────────────────

    @Nested
    @DisplayName("合约交易（位置感知）")
    class ContractTrading {

        @Test
        @DisplayName("SELL 信号 + 无持仓 → 开空仓")
        void shouldOpenShortWhenNoPosition() {
            SignalEvent sellSignal = signal(SignalType.SELL, BigDecimal.ONE);

            Order order = engine.execute(sellSignal, account, BTC_PRICE, TIMESTAMP);

            assertNotNull(order, "SELL 信号应产生非 null 订单");
            assertEquals(OrderStatus.FILLED, order.status(), "订单状态应为 FILLED");
            assertEquals(OrderSide.SHORT, order.side(), "订单方向应为 SHORT");
            assertTrue(account.hasPosition(BTC_USDT), "应已开空仓");
            assertEquals(OrderSide.SHORT, account.getPositionSide(BTC_USDT), "持仓方向应为 SHORT");
        }

        @Test
        @DisplayName("BUY 信号 + 空持仓 → 平空仓")
        void shouldCloseShortOnBuySignal() {
            // 先开空仓
            account.openPosition(BTC_USDT, OrderSide.SHORT,
                    BigDecimal.valueOf(0.1), BTC_PRICE, TIMESTAMP);
            assertEquals(OrderSide.SHORT, account.getPositionSide(BTC_USDT));

            // BUY 信号应平空
            SignalEvent buySignal = signal(SignalType.BUY, BigDecimal.ONE);
            Order order = engine.execute(buySignal, account, BTC_PRICE, TIMESTAMP + 1);

            assertNotNull(order, "BUY 信号应产生非 null 订单");
            assertEquals(OrderStatus.FILLED, order.status(), "订单状态应为 FILLED");
            assertEquals(0, order.quantity().compareTo(BigDecimal.valueOf(0.1)),
                    "平仓订单数量应等于原持仓数量");
            assertFalse(account.hasPosition(BTC_USDT), "平空后不应持有仓位");
        }

        @Test
        @DisplayName("SELL 信号 + 多持仓 → 平多仓（不开空）")
        void shouldCloseLongOnSellSignal() {
            // 先开多仓
            account.openPosition(BTC_USDT, OrderSide.LONG,
                    BigDecimal.valueOf(0.1), BTC_PRICE, TIMESTAMP);
            assertEquals(OrderSide.LONG, account.getPositionSide(BTC_USDT));

            // SELL 信号应平多
            SignalEvent sellSignal = signal(SignalType.SELL, BigDecimal.ONE);
            Order order = engine.execute(sellSignal, account, BTC_PRICE, TIMESTAMP + 1);

            assertNotNull(order, "SELL 信号应产生非 null 订单");
            assertEquals(OrderStatus.FILLED, order.status(), "订单状态应为 FILLED");
            assertEquals(0, order.quantity().compareTo(BigDecimal.valueOf(0.1)),
                    "平仓订单数量应等于原持仓数量");
            assertFalse(account.hasPosition(BTC_USDT), "平多后不应持有仓位");
        }

        @Test
        @DisplayName("BUY 信号 + 无持仓 → 开多仓")
        void shouldOpenLongWhenNoPosition() {
            SignalEvent buySignal = signal(SignalType.BUY, BigDecimal.ONE);

            Order order = engine.execute(buySignal, account, BTC_PRICE, TIMESTAMP);

            assertNotNull(order, "BUY 信号应产生非 null 订单");
            assertEquals(OrderStatus.FILLED, order.status(), "订单状态应为 FILLED");
            assertEquals(OrderSide.LONG, order.side(), "订单方向应为 LONG");
            assertTrue(account.hasPosition(BTC_USDT), "应已开多仓");
            assertEquals(OrderSide.LONG, account.getPositionSide(BTC_USDT), "持仓方向应为 LONG");
        }

        @Test
        @DisplayName("BUY 信号 + 多持仓 → 拒绝同向加仓（不误平仓）")
        void shouldRejectSameSideLongWithoutClosingPosition() {
            account.openPosition(BTC_USDT, OrderSide.LONG,
                    BigDecimal.valueOf(0.1), BTC_PRICE, TIMESTAMP);

            SignalEvent buySignal = signal(SignalType.BUY, BigDecimal.ONE);
            Order order = engine.execute(buySignal, account, BTC_PRICE, TIMESTAMP + 1);

            assertNotNull(order, "同向 BUY 信号应返回拒绝订单");
            assertEquals(OrderStatus.REJECTED, order.status(), "同向 BUY 应被拒绝");
            assertEquals(OrderRejectReason.POSITION_EXISTS, order.rejectReason(),
                    "拒绝原因应为 POSITION_EXISTS");
            assertTrue(account.hasPosition(BTC_USDT), "同向 BUY 不应误平已有多仓");
            assertEquals(OrderSide.LONG, account.getPositionSide(BTC_USDT));
            assertEquals(0, account.getPositionQuantity(BTC_USDT).compareTo(BigDecimal.valueOf(0.1)));
        }

        @Test
        @DisplayName("SELL 信号 + 空持仓 → 拒绝同向加仓（不误平仓）")
        void shouldRejectSameSideShortWithoutClosingPosition() {
            account.openPosition(BTC_USDT, OrderSide.SHORT,
                    BigDecimal.valueOf(0.1), BTC_PRICE, TIMESTAMP);

            SignalEvent sellSignal = signal(SignalType.SELL, BigDecimal.ONE);
            Order order = engine.execute(sellSignal, account, BTC_PRICE, TIMESTAMP + 1);

            assertNotNull(order, "同向 SELL 信号应返回拒绝订单");
            assertEquals(OrderStatus.REJECTED, order.status(), "同向 SELL 应被拒绝");
            assertEquals(OrderRejectReason.POSITION_EXISTS, order.rejectReason(),
                    "拒绝原因应为 POSITION_EXISTS");
            assertTrue(account.hasPosition(BTC_USDT), "同向 SELL 不应误平已有空仓");
            assertEquals(OrderSide.SHORT, account.getPositionSide(BTC_USDT));
            assertEquals(0, account.getPositionQuantity(BTC_USDT).compareTo(BigDecimal.valueOf(0.1)));
        }
    }

    @Test
    @DisplayName("必需 OMS 持久化失败时应在账户变更前停止执行")
    void shouldFailClosedWhenRequiredOmsJournalFails() {
        ExecutionJournal unavailableOms = new ExecutionJournal() {
            @Override
            public void record(Order order, com.tj.crypto.execution.model.OrderEvent event,
                               com.tj.crypto.backtest.portfolio.Trade trade) {
                throw new IllegalStateException("database unavailable");
            }

            @Override
            public boolean requiredForExecution() {
                return true;
            }
        };
        ExecutionEngine durableEngine = new ExecutionEngine(
                riskEngine, new PositionSizer(riskProperties), slippageModel, killSwitch,
                List.of(unavailableOms));

        assertThrows(ExecutionJournalException.class,
                () -> durableEngine.execute(signal(SignalType.BUY, BigDecimal.ONE),
                        account, BTC_PRICE, TIMESTAMP));
        assertFalse(account.hasPosition(BTC_USDT));
        assertEquals(INITIAL_BALANCE, account.getBalance());
    }
}
