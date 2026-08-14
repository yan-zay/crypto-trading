package com.tj.crypto.storage.service;

import com.tj.crypto.backtest.portfolio.Trade;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.common.domain.TradeSide;
import com.tj.crypto.execution.OrderStateMachine;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderEvent;
import com.tj.crypto.execution.model.OrderStatus;
import com.tj.crypto.execution.model.OrderType;
import com.tj.crypto.storage.entity.OmsFillDO;
import com.tj.crypto.storage.entity.OmsOrderDO;
import com.tj.crypto.storage.entity.OmsOrderEventDO;
import com.tj.crypto.storage.entity.TradeRecordDO;
import com.tj.crypto.storage.mapper.OmsFillMapper;
import com.tj.crypto.storage.mapper.OmsOrderEventMapper;
import com.tj.crypto.storage.mapper.OmsOrderMapper;
import com.tj.crypto.storage.mapper.TradeRecordMapper;
import com.tj.crypto.trading.venue.riskreservation.LiveRiskReservationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OmsPersistenceServiceTest {

    @Mock OmsOrderMapper orderMapper;
    @Mock OmsOrderEventMapper eventMapper;
    @Mock OmsFillMapper fillMapper;
    @Mock TradeRecordMapper tradeRecordMapper;
    @Mock LiveRiskReservationMapper riskReservationMapper;

    @Test
    void filledCloseAtomicallyProducesSnapshotEventFillAndTrade() {
        OmsPersistenceService service = new OmsPersistenceService(
                orderMapper, eventMapper, fillMapper, tradeRecordMapper, riskReservationMapper);
        Instrument instrument = Instrument.of(
                Exchange.OKX, MarketType.PERPETUAL, "BTCUSDT");
        long timestamp = 1700000000000L;
        Order created = Order.create("MacdCross", instrument, TradeSide.SELL,
                OrderSide.SHORT, OrderSide.LONG, true, OrderType.MARKET,
                new BigDecimal("0.5"), new BigDecimal("50000"), timestamp);
        Order submitted = OrderStateMachine.transition(
                created, OrderEvent.submitted(created.orderId(), timestamp));
        Order acknowledged = OrderStateMachine.transition(
                submitted, OrderEvent.acknowledged(created.orderId(), timestamp));
        OrderEvent fillEvent = OrderEvent.filled(
                created.orderId(), timestamp + 1, new BigDecimal("49990"), new BigDecimal("0.5"));
        Order filled = OrderStateMachine.transition(acknowledged, fillEvent);
        Trade trade = new Trade(instrument, OrderSide.LONG, new BigDecimal("0.5"),
                new BigDecimal("49000"), new BigDecimal("49990"), timestamp - 60_000,
                timestamp + 1, new BigDecimal("495"), new BigDecimal("5"));

        service.record(filled, fillEvent, trade);

        ArgumentCaptor<OmsOrderDO> order = ArgumentCaptor.forClass(OmsOrderDO.class);
        ArgumentCaptor<OmsOrderEventDO> event = ArgumentCaptor.forClass(OmsOrderEventDO.class);
        ArgumentCaptor<OmsFillDO> fill = ArgumentCaptor.forClass(OmsFillDO.class);
        ArgumentCaptor<TradeRecordDO> tradeRecord = ArgumentCaptor.forClass(TradeRecordDO.class);
        verify(orderMapper).upsert(order.capture());
        verify(eventMapper).insert(event.capture());
        verify(fillMapper).insert(fill.capture());
        verify(tradeRecordMapper).insert(tradeRecord.capture());

        assertThat(order.getValue().getMarketType()).isEqualTo("PERPETUAL");
        assertThat(order.getValue().getReduceOnly()).isTrue();
        assertThat(event.getValue().getEventType()).isEqualTo("FILLED");
        assertThat(fill.getValue().getEventId()).isEqualTo(event.getValue().getEventId());
        assertThat(tradeRecord.getValue().getOrderId()).isEqualTo(filled.orderId());
        assertThat(tradeRecord.getValue().getNetPnl()).isEqualByComparingTo("490");
    }

    @Test
    void nonFillEventDoesNotCreateFillOrTrade() {
        OmsPersistenceService service = new OmsPersistenceService(
                orderMapper, eventMapper, fillMapper, tradeRecordMapper, riskReservationMapper);
        Instrument instrument = Instrument.of(
                Exchange.BINANCE, MarketType.PERPETUAL, "ETHUSDT");
        Order order = Order.create(instrument, OrderSide.LONG,
                OrderType.MARKET, BigDecimal.ONE, BigDecimal.TEN, 1L);

        service.record(order, OrderEvent.created(order.orderId(), 1L), null);

        verify(orderMapper).upsert(org.mockito.ArgumentMatchers.any());
        verify(eventMapper).insert(org.mockito.ArgumentMatchers.any(OmsOrderEventDO.class));
        verifyNoInteractions(fillMapper, tradeRecordMapper);
    }

    @Test
    void initialLiveCommandUsesInsertOnlyClaimInsteadOfSnapshotUpsert() {
        OmsPersistenceService service = new OmsPersistenceService(
                orderMapper, eventMapper, fillMapper, tradeRecordMapper, riskReservationMapper);
        Instrument instrument = Instrument.of(
                Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
        Order order = Order.create(instrument, OrderSide.LONG,
                OrderType.MARKET, BigDecimal.ONE, BigDecimal.TEN, 1L);

        service.recordInitialLiveCommand(order,
                OrderEvent.created(order.orderId(), 1L),
                com.tj.crypto.execution.journal.OmsFillMetadata.empty());

        verify(orderMapper).insert(org.mockito.ArgumentMatchers.any(OmsOrderDO.class));
        verify(eventMapper).insert(org.mockito.ArgumentMatchers.any(OmsOrderEventDO.class));
        verifyNoInteractions(fillMapper, tradeRecordMapper);
        org.mockito.Mockito.verify(orderMapper, org.mockito.Mockito.never())
                .upsert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void liveOmsEventsSynchronizeReservationButUnknownNeverPretendsToReleaseIt() {
        OmsPersistenceService service = new OmsPersistenceService(
                orderMapper, eventMapper, fillMapper, tradeRecordMapper, riskReservationMapper);
        Instrument instrument = Instrument.of(
                Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
        Order partial = new Order("live-order", "live-client", instrument, OrderSide.LONG,
                OrderType.LIMIT, BigDecimal.ONE, BigDecimal.TEN,
                new BigDecimal("0.4"), BigDecimal.TEN, OrderStatus.PARTIALLY_FILLED,
                com.tj.crypto.execution.model.OrderRejectReason.NONE,
                1L, 2L, 0, 0, "strategy", TradeSide.BUY, OrderSide.LONG, false);
        OrderEvent partialEvent = OrderEvent.partiallyFilled(
                partial.orderId(), 3L, BigDecimal.TEN, new BigDecimal("0.4"));

        service.recordVenue(partial, partialEvent, null,
                com.tj.crypto.execution.journal.OmsFillMetadata.empty(), "LIVE");

        verify(riskReservationMapper).syncFromOrder(
                "live-order", "PARTIALLY_FILLED", new BigDecimal("0.4"), 3L);

        org.mockito.Mockito.when(orderMapper.markUnknown("live-order", "TIMEOUT", 4L))
                .thenReturn(1);
        service.markUnknown("live-order", "TIMEOUT", 4L);
        verify(riskReservationMapper).markUnknown("live-order");

        Order cancelled = new Order("live-order", "live-client", instrument, OrderSide.LONG,
                OrderType.LIMIT, BigDecimal.ONE, BigDecimal.TEN,
                new BigDecimal("0.4"), BigDecimal.TEN, OrderStatus.CANCELLED,
                com.tj.crypto.execution.model.OrderRejectReason.NONE,
                1L, 2L, 0, 5L, "strategy", TradeSide.BUY, OrderSide.LONG, false);
        service.recordVenue(cancelled, OrderEvent.cancelled("live-order", 5L), null,
                com.tj.crypto.execution.journal.OmsFillMetadata.empty(), "LIVE");
        verify(riskReservationMapper).syncFromOrder(
                "live-order", "CANCELLED", new BigDecimal("0.4"), 5L);
    }
}
