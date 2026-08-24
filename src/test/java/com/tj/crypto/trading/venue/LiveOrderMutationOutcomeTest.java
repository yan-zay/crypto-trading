package com.tj.crypto.trading.venue;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.common.domain.TradeSide;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderStatus;
import com.tj.crypto.execution.model.OrderType;
import com.tj.crypto.storage.entity.OmsOrderDO;
import com.tj.crypto.storage.mapper.OmsFillMapper;
import com.tj.crypto.storage.mapper.OmsOrderEventMapper;
import com.tj.crypto.storage.mapper.OmsOrderMapper;
import com.tj.crypto.storage.mapper.TradeRecordMapper;
import com.tj.crypto.storage.service.OmsPersistenceService;
import com.tj.crypto.trading.instrument.InstrumentMetadata;
import com.tj.crypto.trading.instrument.InstrumentMetadataService;
import com.tj.crypto.trading.reconciliation.LiveOrderRecoveryGate;
import com.tj.crypto.trading.venue.fencing.ExecutionWriterLeaseService;
import com.tj.crypto.trading.venue.riskreservation.LiveRiskReservationBudget;
import com.tj.crypto.trading.venue.riskreservation.LiveRiskReservationMapper;
import com.tj.crypto.trading.venue.riskreservation.LiveRiskReservationService;
import com.tj.crypto.trading.venue.stream.VenuePrivateEventProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiveOrderMutationOutcomeTest {

    @ParameterizedTest
    @MethodSource("ambiguousFailures")
    void ambiguousMutationFailureBecomesUnknownAndKeepsReservation(
            String venueCode, int httpStatus) {
        Fixture fixture = fixture(new VenueApiException("redacted", venueCode, httpStatus));

        Order result = fixture.service().place(request(), "operator-1");

        assertThat(result.status()).isEqualTo(OrderStatus.UNKNOWN);
        verify(fixture.riskMapper()).markUnknown(result.orderId());
        verify(fixture.riskMapper(), never()).syncFromOrder(
                eq(result.orderId()), eq("REJECTED"), any(), anyLong());
    }

    @Test
    void onlyAllowlistedBusinessRejectionReleasesReservationAsRejected() {
        Fixture fixture = fixture(new VenueApiException("redacted", "-1013", 400));

        Order result = fixture.service().place(request(), "operator-1");

        assertThat(result.status()).isEqualTo(OrderStatus.REJECTED);
        verify(fixture.riskMapper(), never()).markUnknown(result.orderId());
        verify(fixture.riskMapper()).syncFromOrder(
                eq(result.orderId()), eq("REJECTED"), eq(BigDecimal.ZERO), anyLong());
    }

    private static Stream<Arguments> ambiguousFailures() {
        return Stream.of(
                Arguments.of("TRANSPORT", 0),
                Arguments.of("HTTP_ERROR", 408),
                Arguments.of("-1003", 429),
                Arguments.of("UNRECOGNIZED", 400),
                Arguments.of("SERVER_ERROR", 503));
    }

    private Fixture fixture(VenueApiException failure) {
        LiveTradingWriteGuard writeGuard = mock(LiveTradingWriteGuard.class);
        PrivateVenueGateway gateway = mock(PrivateVenueGateway.class);
        when(gateway.exchange()).thenReturn(Exchange.BINANCE);
        when(gateway.place(any(), any())).thenThrow(failure);

        InstrumentMetadataService metadataService = mock(InstrumentMetadataService.class);
        when(metadataService.require(eq(Exchange.BINANCE), eq(MarketType.PERPETUAL),
                eq("BTCUSDT"), anyLong())).thenReturn(metadata());
        VenuePreTradeRiskService riskService = mock(VenuePreTradeRiskService.class);
        when(riskService.validateAndCreateBudget(any(), any(), any(), any()))
                .thenReturn(openingBudget());

        OmsOrderMapper orderMapper = mock(OmsOrderMapper.class);
        when(orderMapper.selectActiveByAccount("account-1")).thenReturn(List.of());
        when(orderMapper.markUnknown(anyString(), eq(failure.venueCode()), anyLong()))
                .thenReturn(1);
        when(orderMapper.selectById(anyString())).thenAnswer(invocation ->
                storedUnknown(invocation.getArgument(0)));
        LiveRiskReservationMapper riskMapper = mock(LiveRiskReservationMapper.class);
        OmsPersistenceService persistence = new OmsPersistenceService(orderMapper,
                mock(OmsOrderEventMapper.class), mock(OmsFillMapper.class),
                mock(TradeRecordMapper.class), riskMapper);

        LiveGovernedOrderReservationService governed = mock(
                LiveGovernedOrderReservationService.class);
        when(governed.authorizeReserveAndRecord(any(), any(), any(), any(), any(),
                any(), any())).thenAnswer(invocation -> {
                    VenueOrderCommand command = invocation.getArgument(4);
                    long now = System.currentTimeMillis();
                    return new LiveOrderAuthorization("authorization-1",
                            LiveMutationKind.SANDBOX_RISK_INCREASE,
                            LiveOrderCommandHasher.hash(command), now - 1_000, now + 30_000, null);
                });

        LiveOrderService service = new LiveOrderService(writeGuard,
                new PrivateVenueGatewayRegistry(List.of(gateway)), metadataService, riskService,
                orderMapper, persistence, mock(VenuePrivateEventProcessor.class),
                mock(LiveRiskReservationService.class));
        service.setWriterLeaseService(mock(ExecutionWriterLeaseService.class));
        service.setRecoveryGate(mock(LiveOrderRecoveryGate.class));
        service.setGovernedReservationService(governed);
        return new Fixture(service, riskMapper);
    }

    private LiveOrderRequest request() {
        return new LiveOrderRequest("account-1", "client-order-1", "strategy-1",
                Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT", TradeSide.BUY,
                OrderSide.LONG, OrderType.LIMIT, BigDecimal.ONE, new BigDecimal("100"),
                null, 1, false, "ISOLATED", "correlation-1");
    }

    private InstrumentMetadata metadata() {
        return new InstrumentMetadata(1, Exchange.BINANCE, MarketType.PERPETUAL,
                "BTCUSDT", "BTCUSDT", "BTC", "USDT", "USDT", "TRADING",
                new BigDecimal("0.1"), new BigDecimal("0.001"),
                new BigDecimal("0.001"), new BigDecimal("100"),
                new BigDecimal("1"), new BigDecimal("1"),
                new BigDecimal("0.0002"), new BigDecimal("0.0004"),
                100, new BigDecimal("0.005"), 8, 1, null, "test-v1");
    }

    private LiveRiskReservationBudget openingBudget() {
        return new LiveRiskReservationBudget("account-1", Exchange.BINANCE,
                MarketType.PERPETUAL, "BTCUSDT", BigDecimal.ONE, new BigDecimal("100"),
                new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("1000"), new BigDecimal("1000"),
                new BigDecimal("1000"), true, System.currentTimeMillis());
    }

    private OmsOrderDO storedUnknown(String orderId) {
        OmsOrderDO order = new OmsOrderDO();
        order.setOrderId(orderId);
        order.setClientOrderId("client-order-1");
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
        order.setPrice(new BigDecimal("100"));
        order.setFilledQuantity(BigDecimal.ZERO);
        order.setStatus("UNKNOWN");
        order.setRejectReason("NONE");
        order.setStrategyId("strategy-1");
        order.setCreatedAtMs(1L);
        return order;
    }

    private record Fixture(LiveOrderService service, LiveRiskReservationMapper riskMapper) {}
}
