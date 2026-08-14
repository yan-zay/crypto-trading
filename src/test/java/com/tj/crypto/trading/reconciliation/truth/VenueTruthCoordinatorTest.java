package com.tj.crypto.trading.reconciliation.truth;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.risk.KillSwitch;
import com.tj.crypto.trading.venue.PrivateVenueGateway;
import com.tj.crypto.trading.venue.PrivateVenueGatewayRegistry;
import com.tj.crypto.trading.venue.VenueAccountSnapshot;
import com.tj.crypto.trading.venue.VenueOrderState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VenueTruthCoordinatorTest {
    @Mock private PrivateVenueGateway gateway;
    @Mock private InternalVenueTruthSource internal;

    private CompositeLiveOrderRecoveryGate gate;
    private KillSwitch killSwitch;
    private VenueTruthCoordinator coordinator;

    @BeforeEach
    void setUp() {
        lenient().when(gateway.exchange()).thenReturn(Exchange.BINANCE);
        lenient().when(gateway.configured()).thenReturn(true);
        gate = new CompositeLiveOrderRecoveryGate();
        gate.markReady();
        killSwitch = new KillSwitch();
        coordinator = new VenueTruthCoordinator(new PrivateVenueGatewayRegistry(List.of(gateway)),
                internal, gate, killSwitch);
    }

    @Test
    void researchOnlyDeploymentWithoutPrivateVenueBlocksLiveGateWithoutHaltingPaper() {
        VenueTruthCoordinator researchOnly = new VenueTruthCoordinator(
                new PrivateVenueGatewayRegistry(List.of()), internal, gate, killSwitch);

        VenueTruthRun run = researchOnly.reconcile(VenueTruthRun.Trigger.STARTUP);

        assertThat(run.converged()).isFalse();
        assertThat(run.differences()).extracting(VenueTruthDifference::code)
                .containsExactly(VenueTruthDifference.Code.NO_CONFIGURED_GATEWAY);
        assertThat(gate.truthState()).isEqualTo(
                com.tj.crypto.trading.reconciliation.LiveOrderRecoveryGate.State.BLOCKED);
        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.NORMAL);
    }

    @Test
    void unsupportedProductionCapabilitiesBlockWithoutCallingMissingEndpoints() {
        when(gateway.truthCapabilities()).thenReturn(VenueTruthCapabilities.partial(
                java.util.EnumSet.of(VenueTruthCapability.BALANCES, VenueTruthCapability.POSITIONS),
                "account snapshots implemented", "orders/fills not implemented"));
        when(internal.capabilities(Exchange.BINANCE))
                .thenReturn(VenueTruthCapabilities.supportedAll("test projection"));

        VenueTruthRun run = coordinator.reconcile(VenueTruthRun.Trigger.STARTUP);

        assertThat(run.converged()).isFalse();
        assertThat(run.differences()).extracting(VenueTruthDifference::code)
                .containsOnly(VenueTruthDifference.Code.VENUE_CAPABILITY_UNSUPPORTED);
        assertThat(run.differences()).extracting(VenueTruthDifference::capability)
                .containsExactlyInAnyOrder(VenueTruthCapability.ACTIVE_ORDERS,
                        VenueTruthCapability.RECENT_FILLS);
        assertThat(gate.truthState()).isEqualTo(com.tj.crypto.trading.reconciliation.LiveOrderRecoveryGate.State.BLOCKED);
        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.HALT);
    }

    @Test
    void completeMatchingEmptySnapshotsCanConverge() {
        fullySupported();
        stubEmptyTruth();

        VenueTruthRun run = coordinator.reconcile(VenueTruthRun.Trigger.MANUAL);

        assertThat(run.converged()).isTrue();
        assertThat(run.differences()).isEmpty();
        assertThat(gate.isReady()).isTrue();
    }

    @Test
    void venueOrphanAndInternalMissingOrderAreStructuredDifferences() {
        fullySupported();
        VenueTruthOrder venueOnly = order("venue-1", "client-1");
        VenueTruthOrder internalOnly = order("venue-2", "client-2");
        when(gateway.activeOrders(any())).thenReturn(List.of(venueOnly));
        when(internal.activeOrders(eq(Exchange.BINANCE), any())).thenReturn(List.of(internalOnly));
        when(gateway.recentFills(any())).thenReturn(List.of());
        when(internal.recentFills(eq(Exchange.BINANCE), any())).thenReturn(List.of());
        stubAccounts();

        VenueTruthRun run = coordinator.reconcile(VenueTruthRun.Trigger.MANUAL);

        assertThat(run.differences()).extracting(VenueTruthDifference::code)
                .containsExactlyInAnyOrder(VenueTruthDifference.Code.VENUE_ORPHAN_ORDER,
                        VenueTruthDifference.Code.INTERNAL_ORDER_MISSING_AT_VENUE);
        assertThat(run.differences()).extracting(VenueTruthDifference::identity)
                .containsExactlyInAnyOrder("venue:venue-1", "venue:venue-2");
    }

    @Test
    void queryFailureDemotesReadyTruthBackToBlocked() {
        fullySupported();
        stubEmptyTruth();
        assertThat(coordinator.reconcile(VenueTruthRun.Trigger.MANUAL).converged()).isTrue();

        when(gateway.activeOrders(any())).thenThrow(new IllegalStateException("venue timeout"));
        VenueTruthRun failed = coordinator.reconcile(VenueTruthRun.Trigger.PERIODIC);

        assertThat(failed.converged()).isFalse();
        assertThat(failed.differences()).extracting(VenueTruthDifference::code)
                .contains(VenueTruthDifference.Code.VENUE_QUERY_FAILED);
        assertThat(gate.truthState()).isEqualTo(com.tj.crypto.trading.reconciliation.LiveOrderRecoveryGate.State.BLOCKED);
    }

    private void fullySupported() {
        when(gateway.truthCapabilities()).thenReturn(VenueTruthCapabilities.supportedAll("fake venue contract"));
        when(internal.capabilities(Exchange.BINANCE))
                .thenReturn(VenueTruthCapabilities.supportedAll("fake durable projection"));
    }

    private void stubEmptyTruth() {
        when(gateway.activeOrders(any())).thenReturn(List.of());
        when(internal.activeOrders(eq(Exchange.BINANCE), any())).thenReturn(List.of());
        when(gateway.recentFills(any())).thenReturn(List.of());
        when(internal.recentFills(eq(Exchange.BINANCE), any())).thenReturn(List.of());
        stubAccounts();
    }

    private void stubAccounts() {
        long now = System.currentTimeMillis();
        when(gateway.account()).thenReturn(new VenueAccountSnapshot("BINANCE", now, List.of(), List.of()));
        when(internal.account(Exchange.BINANCE))
                .thenReturn(new VenueAccountSnapshot("BINANCE", now, List.of(), List.of()));
    }

    private VenueTruthOrder order(String venueId, String clientId) {
        return new VenueTruthOrder(venueId, clientId, MarketType.PERPETUAL, "BTCUSDT",
                VenueOrderState.ACCEPTED, BigDecimal.ONE, BigDecimal.ZERO,
                System.currentTimeMillis());
    }
}
