package com.tj.crypto.trading.venue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.common.domain.TradeSide;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderRejectReason;
import com.tj.crypto.execution.model.OrderStatus;
import com.tj.crypto.execution.model.OrderType;
import com.tj.crypto.governance.approval.ApprovalAction;
import com.tj.crypto.governance.approval.DualControlPolicy;
import com.tj.crypto.governance.approval.DualControlRequest;
import com.tj.crypto.governance.evidence.ControlKind;
import com.tj.crypto.governance.evidence.GovernanceSubject;
import com.tj.crypto.governance.evidence.MediumTermReadinessGate;
import com.tj.crypto.governance.evidence.ReadinessDecision;
import com.tj.crypto.risk.KillSwitch;
import com.tj.crypto.storage.mapper.OmsFillMapper;
import com.tj.crypto.storage.mapper.OmsOrderEventMapper;
import com.tj.crypto.storage.mapper.OmsOrderMapper;
import com.tj.crypto.storage.mapper.TradeRecordMapper;
import com.tj.crypto.storage.service.OmsPersistenceService;
import com.tj.crypto.strategy.lifecycle.StrategyLifecycleStage;
import com.tj.crypto.trading.instrument.InstrumentMetadata;
import com.tj.crypto.trading.instrument.InstrumentMetadataService;
import com.tj.crypto.trading.reconciliation.LiveOrderRecoveryGate;
import com.tj.crypto.trading.venue.binance.BinancePrivateRestGateway;
import com.tj.crypto.trading.venue.fencing.ExecutionWriterLeaseService;
import com.tj.crypto.trading.venue.riskreservation.LiveRiskReservationBudget;
import com.tj.crypto.trading.venue.riskreservation.LiveRiskReservationMapper;
import com.tj.crypto.trading.venue.riskreservation.LiveRiskReservationService;
import com.tj.crypto.trading.venue.stream.VenuePrivateEventProcessor;
import okhttp3.OkHttpClient;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LiveGovernanceHardGateTest {
    private static final long NOW = 2_000_000L;

    @Test
    void liveCanaryOpeningRiskWithoutTrustedGovernanceNeverReachesOrderDispatch() {
        PrivateTradingProperties properties = liveProperties(true);
        LiveTradingWriteGuard writeGuard = new LiveTradingWriteGuard(properties);
        LiveRiskReservationService reservationService = mock(LiveRiskReservationService.class);
        LiveGovernedOrderReservationService governedReservationService =
                new LiveGovernedOrderReservationService(writeGuard,
                        LiveGovernanceDecisionStore.unavailable(), reservationService);

        PrivateVenueGateway gateway = mock(PrivateVenueGateway.class);
        when(gateway.exchange()).thenReturn(Exchange.BINANCE);
        OmsOrderMapper orderMapper = mock(OmsOrderMapper.class);
        when(orderMapper.selectActiveByAccount("account-1")).thenReturn(List.of());
        InstrumentMetadataService metadataService = mock(InstrumentMetadataService.class);
        when(metadataService.require(eq(Exchange.BINANCE), eq(MarketType.PERPETUAL),
                eq("BTCUSDT"), anyLong())).thenReturn(metadata());
        VenuePreTradeRiskService riskService = mock(VenuePreTradeRiskService.class);
        when(riskService.validateAndCreateBudget(any(), any(), any(), any()))
                .thenReturn(openingBudget());

        LiveOrderService service = new LiveOrderService(writeGuard,
                new PrivateVenueGatewayRegistry(List.of(gateway)), metadataService, riskService,
                orderMapper, mock(OmsPersistenceService.class),
                mock(VenuePrivateEventProcessor.class), reservationService);
        service.setWriterLeaseService(mock(ExecutionWriterLeaseService.class));
        service.setRecoveryGate(mock(LiveOrderRecoveryGate.class));
        service.setGovernedReservationService(governedReservationService);

        assertThatThrownBy(() -> service.place(openingRequest(), "operator-1"))
                .isInstanceOf(LiveAuthorizationException.class)
                .hasMessageContaining("release context provider is not configured");

        verify(gateway, never()).place(any(), any());
        verify(gateway, never()).cancel(any(), any());
        verifyNoInteractions(reservationService);
    }

    @Test
    void authorizationRevokedAtGatewayBoundaryRejectsOrderAndReleasesReservation() {
        LiveTradingWriteGuard writeGuard = mock(LiveTradingWriteGuard.class);
        LiveRiskReservationService reservationService = mock(LiveRiskReservationService.class);
        LiveGovernedOrderReservationService governedReservationService =
                new LiveGovernedOrderReservationService(writeGuard,
                        mock(LiveGovernanceDecisionStore.class), reservationService);
        when(writeGuard.authorizeOrder(any(), any(), nullable(DualControlRequest.class),
                nullable(String.class))).thenAnswer(invocation -> {
                    VenueOrderCommand command = invocation.getArgument(0);
                    return new LiveOrderAuthorization("authorization-1",
                            LiveMutationKind.SANDBOX_RISK_INCREASE,
                            LiveOrderCommandHasher.hash(command), NOW, NOW + 5_000, null);
                });
        doNothing().doThrow(new LiveAuthorizationException("authorization revoked"))
                .when(writeGuard).revalidateOrder(any(), any());

        AtomicInteger outboundWrites = new AtomicInteger();
        PrivateVenueGateway gateway = mock(PrivateVenueGateway.class);
        when(gateway.exchange()).thenReturn(Exchange.BINANCE);
        when(gateway.place(any(), any())).thenAnswer(invocation -> {
            writeGuard.revalidateOrder(invocation.getArgument(0), invocation.getArgument(1));
            outboundWrites.incrementAndGet();
            return acceptedSnapshot();
        });
        OmsOrderMapper orderMapper = mock(OmsOrderMapper.class);
        when(orderMapper.selectActiveByAccount("account-1")).thenReturn(List.of());
        InstrumentMetadataService metadataService = mock(InstrumentMetadataService.class);
        when(metadataService.require(eq(Exchange.BINANCE), eq(MarketType.PERPETUAL),
                eq("BTCUSDT"), anyLong())).thenReturn(metadata());
        VenuePreTradeRiskService riskService = mock(VenuePreTradeRiskService.class);
        when(riskService.validateAndCreateBudget(any(), any(), any(), any()))
                .thenReturn(openingBudget());

        OmsOrderEventMapper eventMapper = mock(OmsOrderEventMapper.class);
        LiveRiskReservationMapper liveRiskMapper = mock(LiveRiskReservationMapper.class);
        OmsPersistenceService persistenceService = new OmsPersistenceService(orderMapper,
                eventMapper, mock(OmsFillMapper.class), mock(TradeRecordMapper.class),
                liveRiskMapper);
        LiveOrderService service = new LiveOrderService(writeGuard,
                new PrivateVenueGatewayRegistry(List.of(gateway)), metadataService, riskService,
                orderMapper, persistenceService, mock(VenuePrivateEventProcessor.class),
                reservationService);
        service.setWriterLeaseService(mock(ExecutionWriterLeaseService.class));
        service.setRecoveryGate(mock(LiveOrderRecoveryGate.class));
        service.setGovernedReservationService(governedReservationService);

        Order result = service.place(openingRequest(), "operator-1");

        assertThat(result.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(result.rejectReason()).isEqualTo(OrderRejectReason.AUTHORIZATION_REVOKED);
        assertThat(outboundWrites).hasValue(0);
        verify(gateway).place(any(), any());
        verify(reservationService).reserveAndRecord(any(), any(), any(),
                eq(openingBudget()), any(LiveOrderGovernanceBinding.class));
        verify(liveRiskMapper).syncFromOrder(eq(result.orderId()), eq("REJECTED"),
                eq(BigDecimal.ZERO), anyLong());
    }

    @Test
    void productionGatewayRequiresOpaqueDigestBoundPermitBeforeNetworkAccess() {
        PrivateTradingProperties properties = liveProperties(false);
        LiveTradingWriteGuard writeGuard = new LiveTradingWriteGuard(properties);
        OkHttpClient httpClient = mock(OkHttpClient.class);
        BinancePrivateRestGateway gateway = new BinancePrivateRestGateway(httpClient,
                new ObjectMapper(), properties, writeGuard);
        VenueOrderCommand original = reductionCommand(BigDecimal.ONE);
        LiveOrderAuthorization authorization = writeGuard.authorizeOrder(original,
                reductionBudget(), null, null);
        VenueOrderCommand changed = reductionCommand(new BigDecimal("2"));
        VenueCancelCommand cancel = new VenueCancelCommand(
                original.instrument(), "venue-order-1", original.clientOrderId());

        assertThat(LiveOrderAuthorization.class.getDeclaredConstructors())
                .allMatch(constructor -> !Modifier.isPublic(constructor.getModifiers()));
        assertThatThrownBy(() -> gateway.place(original, null))
                .isInstanceOf(LiveAuthorizationException.class)
                .hasMessageContaining("permit is missing");
        assertThatThrownBy(() -> gateway.place(changed, authorization))
                .isInstanceOf(LiveAuthorizationException.class)
                .hasMessageContaining("mismatched");
        assertThatThrownBy(() -> gateway.cancel(cancel, null))
                .isInstanceOf(LiveAuthorizationException.class)
                .hasMessageContaining("permit is missing");
        verifyNoInteractions(httpClient);
    }

    @Test
    void productionGatewayConsumesExactPermitOnceUnderConcurrentReplay() throws Exception {
        PrivateTradingProperties properties = liveProperties(false);
        properties.getBinance().setApiKey("test-api-key");
        properties.getBinance().setSecretKey("test-secret-key");
        LiveTradingWriteGuard writeGuard = new LiveTradingWriteGuard(properties);
        OkHttpClient httpClient = mock(OkHttpClient.class);
        when(httpClient.newCall(any(Request.class))).thenAnswer(invocation -> {
            Request request = invocation.getArgument(0);
            Call call = mock(Call.class);
            when(call.execute()).thenReturn(new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create("""
                            {"orderId":"venue-order-1","clientOrderId":"client-order-1",
                             "status":"NEW","origQty":"1","executedQty":"0",
                             "avgPrice":"0","updateTime":2000000}
                            """, MediaType.get("application/json")))
                    .build());
            return call;
        });
        BinancePrivateRestGateway gateway = new BinancePrivateRestGateway(httpClient,
                new ObjectMapper(), properties, writeGuard);
        VenueOrderCommand command = reductionCommand(BigDecimal.ONE);
        LiveOrderAuthorization authorization = writeGuard.authorizeOrder(
                command, reductionBudget(), null, null);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<Boolean> attempt = () -> {
            start.await();
            try {
                gateway.place(command, authorization);
                return true;
            } catch (LiveAuthorizationException replayDenied) {
                return false;
            }
        };
        try {
            Future<Boolean> first = pool.submit(attempt);
            Future<Boolean> second = pool.submit(attempt);
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(true, false);
            verify(httpClient, times(1)).newCall(any(Request.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void missingOpeningGovernanceDoesNotBlockVerifiedReductionOrCancellation() {
        PrivateTradingProperties properties = liveProperties(false);
        LiveTradingWriteGuard writeGuard = new LiveTradingWriteGuard(properties);
        VenueOrderCommand reduction = reductionCommand(BigDecimal.ONE);
        LiveOrderAuthorization reductionAuthorization = writeGuard.authorizeOrder(
                reduction, reductionBudget(), null, null);
        VenueCancelCommand cancel = new VenueCancelCommand(
                reduction.instrument(), "venue-order-1", reduction.clientOrderId());
        LiveOrderAuthorization cancelAuthorization = writeGuard.authorizeCancel(cancel);

        assertThat(reductionAuthorization.liveGoverned()).isFalse();
        assertThatCode(() -> writeGuard.revalidateOrder(reduction, reductionAuthorization))
                .doesNotThrowAnyException();
        assertThatCode(() -> writeGuard.revalidateCancel(cancel, cancelAuthorization))
                .doesNotThrowAnyException();
    }

    @Test
    void approvalMustBindExactCommandDigestAndGovernanceSubject() {
        PrivateTradingProperties properties = liveProperties(true);
        GovernanceSubject subject = governanceSubject();
        ResolvedLiveGovernanceContext context = new ResolvedLiveGovernanceContext(
                "assignment-1", 7, "strategy-1", subject.strategyArtifactSha256(),
                StrategyLifecycleStage.CANARY, subject, Instant.ofEpochMilli(NOW + 60_000));
        MediumTermReadinessGate readinessGate = mock(MediumTermReadinessGate.class);
        when(readinessGate.evaluate(subject, NOW)).thenReturn(new ReadinessDecision(true,
                List.of(), Map.of(ControlKind.LEGAL_APPROVAL, "evidence-1")));
        DualControlPolicy dualControlPolicy = mock(DualControlPolicy.class);
        LiveGovernanceDecisionStore decisionStore = mock(LiveGovernanceDecisionStore.class);
        LiveTradingWriteGuard writeGuard = new LiveTradingWriteGuard(properties, readinessGate,
                (strategyName, now) -> context, dualControlPolicy, decisionStore,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        VenueOrderCommand command = openingCommand();

        DualControlRequest wrongCommand = approval("f".repeat(64), subject.sha256());
        DualControlRequest wrongSubject = approval(
                LiveOrderCommandHasher.hash(command), "e".repeat(64));

        assertThatThrownBy(() -> writeGuard.authorizeOrder(command, openingBudget(),
                wrongCommand, "operator-1"))
                .isInstanceOf(LiveAuthorizationException.class)
                .hasMessageContaining("different command, subject");
        assertThatThrownBy(() -> writeGuard.authorizeOrder(command, openingBudget(),
                wrongSubject, "operator-1"))
                .isInstanceOf(LiveAuthorizationException.class)
                .hasMessageContaining("different command, subject");
        verifyNoInteractions(dualControlPolicy, decisionStore);
    }

    @Test
    void finalMutationBoundaryRejectsOpeningWhenKillSwitchChangesAfterAuthorization() {
        PrivateTradingProperties properties = sandboxProperties();
        KillSwitch killSwitch = new KillSwitch();
        LiveTradingWriteGuard guard = new LiveTradingWriteGuard(properties, null, null, null,
                LiveGovernanceDecisionStore.unavailable(), killSwitch,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        VenueOrderCommand command = openingCommand();
        LiveOrderAuthorization authorization = guard.authorizeOrder(
                command, openingBudget(), null, "operator-1");

        killSwitch.activate(KillSwitch.Mode.HALT, "test race", "test");

        assertThatThrownBy(() -> guard.consumeOrder(command, authorization))
                .isInstanceOf(LiveAuthorizationException.class)
                .hasMessageContaining("durable NORMAL kill switch");
        assertThat(authorization.consumed()).isFalse();
    }

    private PrivateTradingProperties liveProperties(boolean openingEnabled) {
        PrivateTradingProperties properties = new PrivateTradingProperties();
        properties.setOperatingMode(PrivateTradingProperties.OperatingMode.LIVE_CANARY);
        properties.setTargetJurisdiction("COUNSEL-APPROVED");
        properties.setLegalApprovalReference("LEGAL-2026-001");
        properties.setGovernanceEnvironment("LIVE");
        properties.setLiveWriteEnabled(openingEnabled);
        properties.getBinance().setEnabled(true);
        properties.getBinance().setWriteEnabled(openingEnabled);
        properties.getBinance().setAccountId("account-1");
        properties.getBinance().setKeyReference("kms://binance/key-1");
        return properties;
    }

    private PrivateTradingProperties sandboxProperties() {
        PrivateTradingProperties properties = new PrivateTradingProperties();
        properties.setOperatingMode(PrivateTradingProperties.OperatingMode.SANDBOX);
        properties.setLiveWriteEnabled(true);
        properties.getBinance().setEnabled(true);
        properties.getBinance().setWriteEnabled(true);
        properties.getBinance().setTestnet(true);
        properties.getBinance().setSpotRestBaseUrl("https://testnet.binance.vision");
        properties.getBinance().setPerpetualRestBaseUrl("https://demo-fapi.binance.com");
        return properties;
    }

    private LiveOrderRequest openingRequest() {
        return new LiveOrderRequest("account-1", "client-order-1", "strategy-1",
                Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT", TradeSide.BUY,
                OrderSide.LONG, OrderType.LIMIT, BigDecimal.ONE, new BigDecimal("100"),
                null, 1, false, "ISOLATED", "correlation-1");
    }

    private VenueOrderCommand openingCommand() {
        return new VenueOrderCommand("account-1", "client-order-1", "strategy-1",
                metadata().instrument(), TradeSide.BUY, OrderSide.LONG, OrderType.LIMIT,
                BigDecimal.ONE, new BigDecimal("100"), false, 1, "ISOLATED");
    }

    private VenueOrderCommand reductionCommand(BigDecimal quantity) {
        return new VenueOrderCommand("account-1", "client-order-1", "strategy-1",
                metadata().instrument(), TradeSide.SELL, OrderSide.LONG, OrderType.LIMIT,
                quantity, new BigDecimal("100"), true, 1, "ISOLATED");
    }

    private LiveRiskReservationBudget openingBudget() {
        return new LiveRiskReservationBudget("account-1", Exchange.BINANCE,
                MarketType.PERPETUAL, "BTCUSDT", BigDecimal.ONE, new BigDecimal("100"),
                new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("1000"), new BigDecimal("1000"),
                new BigDecimal("1000"), true, NOW);
    }

    private LiveRiskReservationBudget reductionBudget() {
        return LiveRiskReservationBudget.reduction("account-1", Exchange.BINANCE,
                MarketType.PERPETUAL, "BTCUSDT", BigDecimal.ONE,
                new BigDecimal("100"), NOW);
    }

    private InstrumentMetadata metadata() {
        return new InstrumentMetadata(1, Exchange.BINANCE, MarketType.PERPETUAL,
                "BTCUSDT", "BTCUSDT", "BTC", "USDT", "USDT", "TRADING",
                new BigDecimal("0.1"), new BigDecimal("0.001"),
                new BigDecimal("0.001"), new BigDecimal("100"),
                new BigDecimal("1"), new BigDecimal("1"),
                new BigDecimal("0.0002"), new BigDecimal("0.0004"),
                100, new BigDecimal("0.005"),
                8, 1, null, "test-v1");
    }

    private VenueOrderSnapshot acceptedSnapshot() {
        return new VenueOrderSnapshot("venue-order-1", "client-order-1", "NEW",
                VenueOrderState.ACCEPTED, BigDecimal.ONE, BigDecimal.ZERO,
                null, NOW, false);
    }

    private GovernanceSubject governanceSubject() {
        String hash = "a".repeat(64);
        return new GovernanceSubject("live-canary", "LIVE", "BINANCE:account-1",
                "b".repeat(40), hash, "sha256:" + "c".repeat(64),
                "d".repeat(64), "e".repeat(64), "f".repeat(64),
                "kms://binance/key-1", "run-1", "policy-v1");
    }

    private DualControlRequest approval(String resourceSha256, String subjectSha256) {
        return new DualControlRequest("approval-1", ApprovalAction.CAPABILITY_TOKEN_ISSUE,
                resourceSha256, subjectSha256, "operator-1", NOW - 1_000,
                NOW + 10_000, List.of());
    }
}
