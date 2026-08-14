package com.tj.crypto.trading.reconciliation;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.common.domain.TradeSide;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderRejectReason;
import com.tj.crypto.execution.model.OrderStatus;
import com.tj.crypto.execution.model.OrderType;
import com.tj.crypto.risk.KillSwitch;
import com.tj.crypto.storage.entity.OmsOrderDO;
import com.tj.crypto.storage.mapper.OmsOrderMapper;
import com.tj.crypto.storage.service.OmsPersistenceService;
import com.tj.crypto.trading.venue.LiveOrderService;
import com.tj.crypto.trading.venue.PrivateTradingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LiveOrderRecoveryCoordinatorTest {
    @Mock
    private OmsOrderMapper orderMapper;
    @Mock
    private OmsPersistenceService persistenceService;
    @Mock
    private LiveOrderService liveOrderService;

    private SimpleMeterRegistry meters;
    private LiveOrderRecoveryCoordinator coordinator;
    private PrivateTradingProperties properties;
    private KillSwitch killSwitch;

    @BeforeEach
    void setUp() {
        properties = new PrivateTradingProperties();
        properties.setLiveWriteEnabled(false);
        properties.setReconciliationBatchSize(25);
        meters = new SimpleMeterRegistry();
        killSwitch = new KillSwitch();
        coordinator = new LiveOrderRecoveryCoordinator(orderMapper, persistenceService,
                liveOrderService, properties, meters, killSwitch);
    }

    @Test
    void scansAConfiguredBatchAndClassifiesResults() {
        OmsOrderDO active = stored("active");
        OmsOrderDO finalOrder = stored("final");
        when(orderMapper.selectActiveLiveOrders(26)).thenReturn(List.of(active, finalOrder));
        when(liveOrderService.reconcile("active")).thenReturn(domain("active", OrderStatus.ACKNOWLEDGED));
        when(liveOrderService.reconcile("final")).thenReturn(domain("final", OrderStatus.FILLED));

        LiveOrderRecoveryRun run = coordinator.reconcilePending(LiveOrderRecoveryCoordinator.Trigger.STARTUP);

        assertThat(run).isEqualTo(new LiveOrderRecoveryRun(2, 2, 1, 1, 0, false));
        assertThat(coordinator.getReadiness()).isEqualTo(LiveOrderRecoveryCoordinator.Readiness.READY);
        assertThat(meters.get("crypto_live_order_recovery_ready").gauge().value()).isEqualTo(1);
        assertThat(meters.get("crypto_live_order_reconciliation_pending").gauge().value()).isEqualTo(2);
        assertThat(meters.get("crypto_live_order_reconciliation_total")
                .tags("trigger", "startup", "outcome", "terminal").counter().count()).isEqualTo(1);
        verify(persistenceService, never()).markUnknown(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), anyLong());
    }

    @Test
    void reconciliationFailureDurablyKeepsOrderUnknownAndContinues() {
        OmsOrderDO failed = stored("failed");
        OmsOrderDO next = stored("next");
        when(orderMapper.selectActiveLiveOrders(26)).thenReturn(List.of(failed, next));
        when(liveOrderService.reconcile("failed")).thenThrow(new IllegalStateException("venue timeout"));
        when(liveOrderService.reconcile("next")).thenReturn(domain("next", OrderStatus.UNKNOWN));

        LiveOrderRecoveryRun run = coordinator.reconcilePending(LiveOrderRecoveryCoordinator.Trigger.PERIODIC);

        assertThat(run).isEqualTo(new LiveOrderRecoveryRun(2, 1, 1, 0, 1, false));
        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.HALT);
        assertThat(coordinator.isRecoveryComplete()).isFalse();
        verify(persistenceService).markUnknown(org.mockito.ArgumentMatchers.eq("failed"),
                startsWith("RECONCILE_ERROR:"), anyLong());
        verify(liveOrderService).reconcile("next");
        assertThat(meters.get("crypto_live_order_reconciliation_total")
                .tags("trigger", "periodic", "outcome", "error").counter().count()).isEqualTo(1);
    }

    @Test
    void refusesStartupScanBeforeDurableKillSwitchRestore() {
        KillSwitch unrestored = mock(KillSwitch.class);
        when(unrestored.isDurableStateRestored()).thenReturn(false);
        when(unrestored.getMode()).thenReturn(KillSwitch.Mode.HALT);
        coordinator = new LiveOrderRecoveryCoordinator(orderMapper, persistenceService,
                liveOrderService, properties, meters, unrestored);

        LiveOrderRecoveryRun run = coordinator.reconcilePending(LiveOrderRecoveryCoordinator.Trigger.STARTUP);

        assertThat(run.recoveryComplete()).isFalse();
        assertThat(coordinator.getReadiness()).isEqualTo(LiveOrderRecoveryCoordinator.Readiness.BLOCKED);
        verify(orderMapper, never()).selectActiveLiveOrders(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void discoveredUnknownKeepsHaltEvenWhenVenueQueryResolvesTerminal() {
        OmsOrderDO unknown = stored("unknown");
        unknown.setStatus(OrderStatus.UNKNOWN.name());
        when(orderMapper.selectActiveLiveOrders(26)).thenReturn(List.of(unknown));
        when(liveOrderService.reconcile("unknown")).thenReturn(domain("unknown", OrderStatus.FILLED));

        LiveOrderRecoveryRun run = coordinator.reconcilePending(LiveOrderRecoveryCoordinator.Trigger.STARTUP);

        assertThat(run.recoveryComplete()).isFalse();
        assertThat(run.terminal()).isEqualTo(1);
        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.HALT);
        assertThat(coordinator.isRecoveryComplete()).isFalse();
    }

    @Test
    void batchSentinelDetectsRemainingPendingOrdersAndBlocksReadiness() {
        properties.setReconciliationBatchSize(2);
        OmsOrderDO first = stored("first");
        OmsOrderDO second = stored("second");
        OmsOrderDO sentinel = stored("sentinel");
        when(orderMapper.selectActiveLiveOrders(3)).thenReturn(List.of(first, second, sentinel));
        when(liveOrderService.reconcile("first")).thenReturn(domain("first", OrderStatus.ACKNOWLEDGED));
        when(liveOrderService.reconcile("second")).thenReturn(domain("second", OrderStatus.ACKNOWLEDGED));

        LiveOrderRecoveryRun run = coordinator.reconcilePending(LiveOrderRecoveryCoordinator.Trigger.STARTUP);

        assertThat(run.backlogRemaining()).isTrue();
        assertThat(run.recoveryComplete()).isFalse();
        assertThat(run.reconciled()).isEqualTo(2);
        verify(liveOrderService, never()).reconcile("sentinel");
        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.HALT);
    }

    @Test
    void scanFailureBlocksReadinessAndForcesHalt() {
        when(orderMapper.selectActiveLiveOrders(26)).thenThrow(new IllegalStateException("database unavailable"));

        LiveOrderRecoveryRun run = coordinator.reconcilePending(LiveOrderRecoveryCoordinator.Trigger.PERIODIC);

        assertThat(run.failed()).isEqualTo(1);
        assertThat(run.recoveryComplete()).isFalse();
        assertThat(coordinator.getReadiness()).isEqualTo(LiveOrderRecoveryCoordinator.Readiness.BLOCKED);
        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.HALT);
    }

    private OmsOrderDO stored(String id) {
        OmsOrderDO order = new OmsOrderDO();
        order.setOrderId(id);
        order.setExchange("BINANCE");
        return order;
    }

    private Order domain(String id, OrderStatus status) {
        return new Order(id, "client-" + id,
                Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT"),
                OrderSide.LONG, OrderType.LIMIT, BigDecimal.ONE, BigDecimal.TEN,
                status == OrderStatus.FILLED ? BigDecimal.ONE : BigDecimal.ZERO,
                status == OrderStatus.FILLED ? BigDecimal.TEN : null, status,
                OrderRejectReason.NONE, 1L, 2L,
                status == OrderStatus.FILLED ? 3L : 0L, 0L, "strategy",
                TradeSide.BUY, OrderSide.LONG, false);
    }
}
