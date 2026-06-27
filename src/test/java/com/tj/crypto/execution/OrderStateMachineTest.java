package com.tj.crypto.execution;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderEvent;
import com.tj.crypto.execution.model.OrderRejectReason;
import com.tj.crypto.execution.model.OrderStatus;
import com.tj.crypto.execution.model.OrderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OrderStateMachine 单元测试。
 *
 * 覆盖：
 * - 所有合法状态转换路径
 * - 非法状态转换（应抛 IllegalStateException）
 * - 部分成交累计计算
 * - 加权平均成交价格计算
 */
@DisplayName("OrderStateMachine")
class OrderStateMachineTest {

    private static final Instrument BTC_USDT = new Instrument(
            Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT", "BTC", "USDT"
    );
    private static final long T1 = 1700000000000L;
    private static final long T2 = 1700000001000L;
    private static final long T3 = 1700000002000L;
    private static final long T4 = 1700000003000L;
    private static final long T5 = 1700000004000L;

    private Order createOrder() {
        return Order.create(BTC_USDT, OrderSide.LONG, OrderType.MARKET,
                BigDecimal.valueOf(1.0), BigDecimal.valueOf(50000), T1);
    }

    // ─── 合法状态转换 ────────────────────────────────────────────

    @Nested
    @DisplayName("CREATED → SUBMITTED")
    class CreatedToSubmitted {

        @Test
        @DisplayName("CREATED 经 SUBMITTED 事件转为 SUBMITTED")
        void shouldTransitionToSubmitted() {
            Order order = createOrder();
            assertEquals(OrderStatus.CREATED, order.status());

            Order submitted = OrderStateMachine.transition(order,
                    OrderEvent.submitted(order.orderId(), T2));

            assertEquals(OrderStatus.SUBMITTED, submitted.status());
            assertEquals(T2, submitted.submittedAt());
            // 其他字段不变
            assertEquals(order.orderId(), submitted.orderId());
            assertEquals(order.instrument(), submitted.instrument());
            assertEquals(order.quantity(), submitted.quantity());
        }
    }

    @Nested
    @DisplayName("SUBMITTED → ACKNOWLEDGED")
    class SubmittedToAcknowledged {

        @Test
        @DisplayName("SUBMITTED 经 ACKNOWLEDGED 事件转为 ACKNOWLEDGED")
        void shouldTransitionToAcknowledged() {
            Order order = OrderStateMachine.transition(createOrder(),
                    OrderEvent.submitted(createOrder().orderId(), T2));

            Order ack = OrderStateMachine.transition(order,
                    OrderEvent.acknowledged(order.orderId(), T3));

            assertEquals(OrderStatus.ACKNOWLEDGED, ack.status());
        }
    }

    @Nested
    @DisplayName("ACKNOWLEDGED → FILLED")
    class AcknowledgedToFilled {

        @Test
        @DisplayName("ACKNOWLEDGED 经 FILLED 事件直接转为 FILLED")
        void shouldTransitionToFilled() {
            Order order = advanceToAcknowledged();

            Order filled = OrderStateMachine.transition(order,
                    OrderEvent.filled(order.orderId(), T4,
                            BigDecimal.valueOf(50100), BigDecimal.valueOf(1.0)));

            assertEquals(OrderStatus.FILLED, filled.status());
            assertEquals(BigDecimal.valueOf(1.0), filled.filledQuantity());
            assertEquals(BigDecimal.valueOf(50100), filled.avgFillPrice());
            assertEquals(T4, filled.filledAt());
        }
    }

    @Nested
    @DisplayName("ACKNOWLEDGED → PARTIALLY_FILLED → FILLED")
    class PartialFillFlow {

        @Test
        @DisplayName("部分成交后完全成交，数量和均价正确累计")
        void shouldAccumulatePartialFills() {
            Order order = advanceToAcknowledged();

            // 第一笔部分成交：0.3 @ 50000
            Order partial1 = OrderStateMachine.transition(order,
                    OrderEvent.partiallyFilled(order.orderId(), T4,
                            BigDecimal.valueOf(50000), BigDecimal.valueOf(0.3)));
            assertEquals(OrderStatus.PARTIALLY_FILLED, partial1.status());
            assertEquals(0, BigDecimal.valueOf(0.3).compareTo(partial1.filledQuantity()));
            assertEquals(0, BigDecimal.valueOf(50000).compareTo(partial1.avgFillPrice()));

            // 第二笔部分成交：0.3 @ 50200
            Order partial2 = OrderStateMachine.transition(partial1,
                    OrderEvent.partiallyFilled(partial1.orderId(), T5,
                            BigDecimal.valueOf(50200), BigDecimal.valueOf(0.3)));
            assertEquals(OrderStatus.PARTIALLY_FILLED, partial2.status());
            assertEquals(0, BigDecimal.valueOf(0.6).compareTo(partial2.filledQuantity()));
            // 均价 = (50000*0.3 + 50200*0.3) / 0.6 = 50100
            assertEquals(0, BigDecimal.valueOf(50100).compareTo(partial2.avgFillPrice()));

            // 最后完全成交：0.4 @ 50300
            Order filled = OrderStateMachine.transition(partial2,
                    OrderEvent.filled(partial2.orderId(), T5 + 1,
                            BigDecimal.valueOf(50300), BigDecimal.valueOf(0.4)));
            assertEquals(OrderStatus.FILLED, filled.status());
            assertEquals(0, BigDecimal.valueOf(1.0).compareTo(filled.filledQuantity()));
            // 均价 = (50100*0.6 + 50300*0.4) / 1.0 = 50180
            assertEquals(0, BigDecimal.valueOf(50180).compareTo(filled.avgFillPrice()));
        }
    }

    @Nested
    @DisplayName("ACKNOWLEDGED → CANCEL_REQUESTED → CANCELLED")
    class CancelFlow {

        @Test
        @DisplayName("正常撤单流程")
        void shouldCancelSuccessfully() {
            Order order = advanceToAcknowledged();

            Order cancelReq = OrderStateMachine.transition(order,
                    OrderEvent.cancelRequested(order.orderId(), T4));
            assertEquals(OrderStatus.CANCEL_REQUESTED, cancelReq.status());

            Order cancelled = OrderStateMachine.transition(cancelReq,
                    OrderEvent.cancelled(cancelReq.orderId(), T5));
            assertEquals(OrderStatus.CANCELLED, cancelled.status());
            assertEquals(T5, cancelled.cancelledAt());
        }

        @Test
        @DisplayName("撤单请求期间仍可成交（交易所已撮合）")
        void shouldFillEvenAfterCancelRequested() {
            Order order = advanceToAcknowledged();

            Order cancelReq = OrderStateMachine.transition(order,
                    OrderEvent.cancelRequested(order.orderId(), T4));

            // 撤单请求期间交易所已撮合
            Order filled = OrderStateMachine.transition(cancelReq,
                    OrderEvent.filled(cancelReq.orderId(), T5,
                            BigDecimal.valueOf(50000), BigDecimal.valueOf(1.0)));
            assertEquals(OrderStatus.FILLED, filled.status());
        }
    }

    @Nested
    @DisplayName("任意活跃状态 → REJECTED")
    class RejectionPaths {

        @Test
        @DisplayName("CREATED 可直接拒绝")
        void shouldRejectFromCreated() {
            Order order = createOrder();
            Order rejected = OrderStateMachine.transition(order,
                    OrderEvent.rejected(order.orderId(), T2, OrderRejectReason.RISK_REJECTED));
            assertEquals(OrderStatus.REJECTED, rejected.status());
            assertEquals(OrderRejectReason.RISK_REJECTED, rejected.rejectReason());
        }

        @Test
        @DisplayName("SUBMITTED 可拒绝")
        void shouldRejectFromSubmitted() {
            Order order = OrderStateMachine.transition(createOrder(),
                    OrderEvent.submitted(createOrder().orderId(), T2));
            Order rejected = OrderStateMachine.transition(order,
                    OrderEvent.rejected(order.orderId(), T3, OrderRejectReason.RISK_REJECTED));
            assertEquals(OrderStatus.REJECTED, rejected.status());
        }

        @Test
        @DisplayName("ACKNOWLEDGED 可拒绝")
        void shouldRejectFromAcknowledged() {
            Order order = advanceToAcknowledged();
            Order rejected = OrderStateMachine.transition(order,
                    OrderEvent.rejected(order.orderId(), T4, OrderRejectReason.INSUFFICIENT_BALANCE));
            assertEquals(OrderStatus.REJECTED, rejected.status());
            assertEquals(OrderRejectReason.INSUFFICIENT_BALANCE, rejected.rejectReason());
        }

        @Test
        @DisplayName("PARTIALLY_FILLED 可拒绝")
        void shouldRejectFromPartiallyFilled() {
            Order order = advanceToAcknowledged();
            Order partial = OrderStateMachine.transition(order,
                    OrderEvent.partiallyFilled(order.orderId(), T4,
                            BigDecimal.valueOf(50000), BigDecimal.valueOf(0.5)));
            Order rejected = OrderStateMachine.transition(partial,
                    OrderEvent.rejected(partial.orderId(), T5, OrderRejectReason.RISK_REJECTED));
            assertEquals(OrderStatus.REJECTED, rejected.status());
            // 部分成交数量应保留
            assertEquals(0, BigDecimal.valueOf(0.5).compareTo(rejected.filledQuantity()));
        }
    }

    @Nested
    @DisplayName("ACKNOWLEDGED → EXPIRED")
    class ExpiryPath {

        @Test
        @DisplayName("ACKNOWLEDGED 可过期")
        void shouldExpireFromAcknowledged() {
            Order order = advanceToAcknowledged();
            Order expired = OrderStateMachine.transition(order,
                    OrderEvent.expired(order.orderId(), T4));
            assertEquals(OrderStatus.EXPIRED, expired.status());
        }
    }

    // ─── 非法状态转换 ────────────────────────────────────────────

    @Nested
    @DisplayName("非法状态转换")
    class InvalidTransitions {

        @Test
        @DisplayName("FILLED 不能再转换")
        void shouldRejectTransitionFromFilled() {
            Order filled = advanceToFilled();
            assertThrows(IllegalStateException.class, () ->
                    OrderStateMachine.transition(filled,
                            OrderEvent.cancelRequested(filled.orderId(), T5)),
                    "终态 FILLED 不应允许任何转换");
        }

        @Test
        @DisplayName("CANCELLED 不能再转换")
        void shouldRejectTransitionFromCancelled() {
            Order order = advanceToAcknowledged();
            Order cancelReq = OrderStateMachine.transition(order,
                    OrderEvent.cancelRequested(order.orderId(), T4));
            Order cancelled = OrderStateMachine.transition(cancelReq,
                    OrderEvent.cancelled(cancelReq.orderId(), T5));

            assertThrows(IllegalStateException.class, () ->
                    OrderStateMachine.transition(cancelled,
                            OrderEvent.submitted(cancelled.orderId(), T5 + 1)),
                    "终态 CANCELLED 不应允许任何转换");
        }

        @Test
        @DisplayName("REJECTED 不能再转换")
        void shouldRejectTransitionFromRejected() {
            Order order = createOrder();
            Order rejected = OrderStateMachine.transition(order,
                    OrderEvent.rejected(order.orderId(), T2, OrderRejectReason.RISK_REJECTED));

            assertThrows(IllegalStateException.class, () ->
                    OrderStateMachine.transition(rejected,
                            OrderEvent.submitted(rejected.orderId(), T3)),
                    "终态 REJECTED 不应允许任何转换");
        }

        @Test
        @DisplayName("EXPIRED 不能再转换")
        void shouldRejectTransitionFromExpired() {
            Order order = advanceToAcknowledged();
            Order expired = OrderStateMachine.transition(order,
                    OrderEvent.expired(order.orderId(), T4));

            assertThrows(IllegalStateException.class, () ->
                    OrderStateMachine.transition(expired,
                            OrderEvent.submitted(expired.orderId(), T5)),
                    "终态 EXPIRED 不应允许任何转换");
        }

        @Test
        @DisplayName("CREATED 不能直接跳到 ACKNOWLEDGED")
        void shouldRejectSkippingSubmitted() {
            Order order = createOrder();
            assertThrows(IllegalStateException.class, () ->
                    OrderStateMachine.transition(order,
                            OrderEvent.acknowledged(order.orderId(), T2)),
                    "CREATED 不应直接跳到 ACKNOWLEDGED");
        }

        @Test
        @DisplayName("CREATED 不能直接跳到 FILLED")
        void shouldRejectDirectFillFromCreated() {
            Order order = createOrder();
            assertThrows(IllegalStateException.class, () ->
                    OrderStateMachine.transition(order,
                            OrderEvent.filled(order.orderId(), T2,
                                    BigDecimal.valueOf(50000), BigDecimal.ONE)),
                    "CREATED 不应直接跳到 FILLED");
        }

        @Test
        @DisplayName("SUBMITTED 不能直接跳到 FILLED")
        void shouldRejectDirectFillFromSubmitted() {
            Order order = OrderStateMachine.transition(createOrder(),
                    OrderEvent.submitted(createOrder().orderId(), T2));
            assertThrows(IllegalStateException.class, () ->
                    OrderStateMachine.transition(order,
                            OrderEvent.filled(order.orderId(), T3,
                                    BigDecimal.valueOf(50000), BigDecimal.ONE)),
                    "SUBMITTED 不应直接跳到 FILLED");
        }

        @Test
        @DisplayName("CREATED 不能请求撤单")
        void shouldRejectCancelFromCreated() {
            Order order = createOrder();
            assertThrows(IllegalStateException.class, () ->
                    OrderStateMachine.transition(order,
                            OrderEvent.cancelRequested(order.orderId(), T2)),
                    "CREATED 不应允许撤单请求");
        }

        @Test
        @DisplayName("SUBMITTED 不能请求撤单")
        void shouldRejectCancelFromSubmitted() {
            Order order = OrderStateMachine.transition(createOrder(),
                    OrderEvent.submitted(createOrder().orderId(), T2));
            assertThrows(IllegalStateException.class, () ->
                    OrderStateMachine.transition(order,
                            OrderEvent.cancelRequested(order.orderId(), T3)),
                    "SUBMITTED 不应允许撤单请求");
        }
    }

    // ─── 不可变性验证 ────────────────────────────────────────────

    @Nested
    @DisplayName("不可变性")
    class Immutability {

        @Test
        @DisplayName("transition 不修改原 Order 对象")
        void shouldNotMutateOriginalOrder() {
            Order original = createOrder();
            OrderStatus originalStatus = original.status();
            long originalSubmittedAt = original.submittedAt();

            OrderStateMachine.transition(original,
                    OrderEvent.submitted(original.orderId(), T2));

            // 原对象不变
            assertEquals(originalStatus, original.status());
            assertEquals(originalSubmittedAt, original.submittedAt());
        }
    }

    // ─── 辅助方法 ────────────────────────────────────────────────

    private Order advanceToAcknowledged() {
        Order created = createOrder();
        Order submitted = OrderStateMachine.transition(created,
                OrderEvent.submitted(created.orderId(), T2));
        return OrderStateMachine.transition(submitted,
                OrderEvent.acknowledged(submitted.orderId(), T3));
    }

    private Order advanceToFilled() {
        Order ack = advanceToAcknowledged();
        return OrderStateMachine.transition(ack,
                OrderEvent.filled(ack.orderId(), T4,
                        BigDecimal.valueOf(50000), BigDecimal.valueOf(1.0)));
    }
}
