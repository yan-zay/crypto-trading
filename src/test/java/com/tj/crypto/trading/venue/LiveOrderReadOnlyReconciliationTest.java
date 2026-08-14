package com.tj.crypto.trading.venue;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.execution.model.OrderStatus;
import com.tj.crypto.storage.entity.OmsOrderDO;
import com.tj.crypto.storage.mapper.OmsOrderMapper;
import com.tj.crypto.storage.service.OmsPersistenceService;
import com.tj.crypto.trading.instrument.InstrumentMetadataService;
import com.tj.crypto.trading.venue.riskreservation.LiveRiskReservationService;
import com.tj.crypto.trading.venue.stream.VenuePrivateEventProcessor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class LiveOrderReadOnlyReconciliationTest {

    @Test
    void writeGatesCanRemainClosedWhileRecoveryOnlyQueriesTheVenue() {
        PrivateTradingProperties properties = new PrivateTradingProperties();
        properties.setLiveWriteEnabled(false);
        properties.getBinance().setEnabled(false);
        properties.getBinance().setWriteEnabled(false);
        PrivateVenueGateway gateway = mock(PrivateVenueGateway.class);
        when(gateway.exchange()).thenReturn(Exchange.BINANCE);
        when(gateway.configured()).thenReturn(true);
        when(gateway.query(any())).thenReturn(new VenueOrderSnapshot("venue-1", null,
                "NEW", VenueOrderState.ACCEPTED, BigDecimal.ONE, BigDecimal.ZERO,
                null, 100L, false));
        OmsOrderMapper mapper = mock(OmsOrderMapper.class);
        OmsOrderDO stored = storedUnknown();
        when(mapper.selectById("order-1")).thenReturn(stored);

        LiveOrderService service = new LiveOrderService(new LiveTradingWriteGuard(properties),
                new PrivateVenueGatewayRegistry(List.of(gateway)),
                mock(InstrumentMetadataService.class), mock(VenuePreTradeRiskService.class),
                mapper, mock(OmsPersistenceService.class), mock(VenuePrivateEventProcessor.class),
                mock(LiveRiskReservationService.class));

        assertThat(service.reconcile("order-1").status()).isEqualTo(OrderStatus.UNKNOWN);
        verify(gateway).query(any());
        verify(gateway, never()).place(any());
        verify(gateway, never()).cancel(any());
    }

    @Test
    void terminalLiveOrderIsNotQueriedAfterARecoveryScanRace() {
        PrivateVenueGateway gateway = mock(PrivateVenueGateway.class);
        when(gateway.exchange()).thenReturn(Exchange.BINANCE);
        OmsOrderMapper mapper = mock(OmsOrderMapper.class);
        OmsOrderDO stored = storedUnknown();
        stored.setStatus("FILLED");
        stored.setFilledQuantity(BigDecimal.ONE);
        stored.setAvgFillPrice(BigDecimal.TEN);
        stored.setFilledAtMs(10L);
        when(mapper.selectById("order-1")).thenReturn(stored);

        LiveOrderService service = new LiveOrderService(
                new LiveTradingWriteGuard(new PrivateTradingProperties()),
                new PrivateVenueGatewayRegistry(List.of(gateway)),
                mock(InstrumentMetadataService.class), mock(VenuePreTradeRiskService.class),
                mapper, mock(OmsPersistenceService.class), mock(VenuePrivateEventProcessor.class),
                mock(LiveRiskReservationService.class));

        assertThat(service.reconcile("order-1").status()).isEqualTo(OrderStatus.FILLED);
        verify(gateway, never()).query(any());
        verify(gateway, never()).place(any());
        verify(gateway, never()).cancel(any());
    }

    @Test
    void unknownOrderCanBeCancelledWhileTheOpeningGateIsClosed() {
        PrivateTradingProperties properties = new PrivateTradingProperties();
        properties.setOperatingMode(PrivateTradingProperties.OperatingMode.LIVE_CANARY);
        properties.setTargetJurisdiction("COUNSEL-APPROVED");
        properties.setLegalApprovalReference("LEGAL-2026-001");
        properties.setLiveWriteEnabled(false);
        properties.getBinance().setEnabled(true);
        properties.getBinance().setWriteEnabled(true);
        PrivateVenueGateway gateway = mock(PrivateVenueGateway.class);
        when(gateway.exchange()).thenReturn(Exchange.BINANCE);
        when(gateway.cancel(any())).thenReturn(new VenueOrderSnapshot(
                "venue-1", "client-1", "CANCELED", VenueOrderState.CANCELLED,
                BigDecimal.ONE, BigDecimal.ZERO, null, 100L, true));
        OmsOrderMapper mapper = mock(OmsOrderMapper.class);
        OmsOrderDO unknown = storedUnknown();
        OmsOrderDO cancelRequested = storedUnknown();
        cancelRequested.setStatus("CANCEL_REQUESTED");
        when(mapper.selectById("order-1")).thenReturn(unknown, cancelRequested);
        OmsPersistenceService persistence = mock(OmsPersistenceService.class);

        LiveOrderService service = new LiveOrderService(new LiveTradingWriteGuard(properties),
                new PrivateVenueGatewayRegistry(List.of(gateway)),
                mock(InstrumentMetadataService.class), mock(VenuePreTradeRiskService.class),
                mapper, persistence, mock(VenuePrivateEventProcessor.class),
                mock(LiveRiskReservationService.class));

        assertThat(service.cancel("order-1").status()).isEqualTo(OrderStatus.CANCELLED);
        verify(gateway).cancel(any());
        verify(persistence, times(2)).recordVenue(any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.eq("LIVE"));
    }

    private OmsOrderDO storedUnknown() {
        OmsOrderDO order = new OmsOrderDO();
        order.setOrderId("order-1");
        order.setClientOrderId("client-1");
        order.setOrderSource("LIVE");
        order.setAccountId("account-1");
        order.setExchange("BINANCE");
        order.setMarketType("PERPETUAL");
        order.setSymbol("BTCUSDT");
        order.setTradeSide("BUY");
        order.setRequestedSide("LONG");
        order.setPositionSide("LONG");
        order.setReduceOnly(false);
        order.setOrderType("LIMIT");
        order.setQuantity(BigDecimal.ONE);
        order.setPrice(BigDecimal.TEN);
        order.setFilledQuantity(BigDecimal.ZERO);
        order.setStatus("UNKNOWN");
        order.setRejectReason("NONE");
        order.setStrategyId("strategy");
        order.setCreatedAtMs(1L);
        return order;
    }
}
