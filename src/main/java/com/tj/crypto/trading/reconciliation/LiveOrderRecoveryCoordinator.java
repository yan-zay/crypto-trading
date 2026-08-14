package com.tj.crypto.trading.reconciliation;

import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderStatus;
import com.tj.crypto.risk.KillSwitch;
import com.tj.crypto.storage.entity.OmsOrderDO;
import com.tj.crypto.storage.mapper.OmsOrderMapper;
import com.tj.crypto.storage.service.OmsPersistenceService;
import com.tj.crypto.trading.venue.LiveOrderService;
import com.tj.crypto.trading.venue.PrivateTradingProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Read-only venue recovery loop for non-terminal live OMS orders.
 *
 * <p>The loop can run while the live write gates are disabled: it invokes only
 * {@link LiveOrderService#reconcile(String)}, whose venue boundary is a signed query.
 * Failures never retry placement/cancellation and force the durable order to UNKNOWN.
 */
@Slf4j
@Component
public class LiveOrderRecoveryCoordinator {
    public enum Trigger { STARTUP, PERIODIC }
    public enum Readiness { PENDING, READY, BLOCKED }

    private final OmsOrderMapper orderMapper;
    private final OmsPersistenceService persistenceService;
    private final LiveOrderService liveOrderService;
    private final PrivateTradingProperties properties;
    private final MeterRegistry meterRegistry;
    private final KillSwitch killSwitch;
    private final LiveOrderRecoveryGate recoveryGate;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger pendingGauge = new AtomicInteger();
    private final AtomicInteger readyGauge = new AtomicInteger();
    private final AtomicReference<Readiness> readiness = new AtomicReference<>(Readiness.PENDING);

    @Autowired
    public LiveOrderRecoveryCoordinator(OmsOrderMapper orderMapper,
                                         OmsPersistenceService persistenceService,
                                         LiveOrderService liveOrderService,
                                         PrivateTradingProperties properties,
                                         MeterRegistry meterRegistry,
                                         KillSwitch killSwitch,
                                         LiveOrderRecoveryGate recoveryGate) {
        this.orderMapper = orderMapper;
        this.persistenceService = persistenceService;
        this.liveOrderService = liveOrderService;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.killSwitch = killSwitch;
        this.recoveryGate = recoveryGate;
        Gauge.builder("crypto_live_order_reconciliation_pending", pendingGauge, AtomicInteger::get)
                .description("Live OMS orders discovered by the most recent reconciliation scan")
                .register(meterRegistry);
        Gauge.builder("crypto_live_order_recovery_ready", readyGauge, AtomicInteger::get)
                .description("1 only after a complete live OMS recovery scan without uncertainty")
                .register(meterRegistry);
    }

    /** Compatibility constructor for isolated tests. */
    public LiveOrderRecoveryCoordinator(OmsOrderMapper orderMapper,
                                        OmsPersistenceService persistenceService,
                                        LiveOrderService liveOrderService,
                                        PrivateTradingProperties properties,
                                        MeterRegistry meterRegistry,
                                        KillSwitch killSwitch) {
        this(orderMapper, persistenceService, liveOrderService, properties, meterRegistry,
                killSwitch, new LiveOrderRecoveryGate());
    }

    @EventListener(ApplicationReadyEvent.class)
    @org.springframework.core.annotation.Order(Ordered.HIGHEST_PRECEDENCE + 100)
    public void recoverOnStartup() {
        reconcilePending(Trigger.STARTUP);
    }

    @Scheduled(fixedDelayString = "${crypto.private-trading.reconciliation-interval-ms:60000}")
    public void periodicReconciliation() {
        reconcilePending(Trigger.PERIODIC);
    }

    public LiveOrderRecoveryRun reconcilePending(Trigger trigger) {
        if (!running.compareAndSet(false, true)) {
            count(trigger, "skipped");
            return LiveOrderRecoveryRun.skippedRun();
        }
        try {
            if (!killSwitch.isDurableStateRestored() || !killSwitch.isPersistenceHealthy()) {
                block("LIVE_RECOVERY_KILL_SWITCH_NOT_RESTORED",
                        "Live recovery cannot start before durable kill-switch restoration");
                count(trigger, "kill_switch_not_restored");
                return new LiveOrderRecoveryRun(0, 0, 0, 0, 1,
                        false, false, false);
            }
            int batchSize = Math.max(1, Math.min(properties.getReconciliationBatchSize(), 1_000));
            List<OmsOrderDO> active = orderMapper.selectActiveLiveOrders(
                    batchSize + 1);
            pendingGauge.set(active.size());
            boolean backlogRemaining = active.size() > batchSize;
            List<OmsOrderDO> candidates = backlogRemaining ? active.subList(0, batchSize) : active;
            boolean unknownDiscovered = active.stream()
                    .anyMatch(candidate -> OrderStatus.UNKNOWN.name().equalsIgnoreCase(candidate.getStatus()));
            if (backlogRemaining) {
                block("LIVE_RECOVERY_BACKLOG",
                        "Live recovery batch was exhausted while durable orders remain pending");
            }
            if (unknownDiscovered) {
                block("LIVE_RECOVERY_UNKNOWN_DISCOVERED",
                        "Live UNKNOWN order discovered; explicit review is required");
            }
            int reconciled = 0;
            int stillActive = 0;
            int terminal = 0;
            int failed = 0;
            boolean unknownResult = false;
            for (OmsOrderDO candidate : candidates) {
                try {
                    Order result = liveOrderService.reconcile(candidate.getOrderId());
                    reconciled++;
                    if (result.status().isActive()) {
                        stillActive++;
                        if (result.status() == OrderStatus.UNKNOWN) {
                            unknownResult = true;
                            block("LIVE_RECOVERY_UNKNOWN_RESULT",
                                    "Venue reconciliation left a live order UNKNOWN");
                            count(trigger, "unknown");
                        } else {
                            count(trigger, "active");
                        }
                    } else {
                        terminal++;
                        count(trigger, "terminal");
                    }
                } catch (RuntimeException e) {
                    failed++;
                    block("LIVE_RECOVERY_QUERY_FAILED",
                            "Venue reconciliation query failed; order outcome is uncertain");
                    markUnknown(candidate, e);
                    count(trigger, "error");
                    log.error("Live order reconciliation failed; order remains UNKNOWN: orderId={}, exchange={}",
                            candidate.getOrderId(), candidate.getExchange(), e);
                }
            }
            boolean complete = !backlogRemaining && !unknownDiscovered && !unknownResult && failed == 0;
            if (complete) {
                setReadiness(Readiness.READY);
            } else {
                setReadiness(Readiness.BLOCKED);
            }
            log.info("Live order reconciliation completed: trigger={}, discovered={}, reconciled={}, "
                            + "active={}, terminal={}, failed={}, backlog={}, recoveryComplete={}",
                    trigger, active.size(), reconciled, stillActive, terminal, failed,
                    backlogRemaining, complete);
            return new LiveOrderRecoveryRun(active.size(), reconciled, stillActive, terminal, failed,
                    false, backlogRemaining, complete);
        } catch (RuntimeException e) {
            pendingGauge.set(0);
            block("LIVE_RECOVERY_SCAN_FAILED",
                    "Unable to scan durable live orders; order truth is uncertain");
            count(trigger, "scan_error");
            log.error("Unable to scan durable live orders for reconciliation", e);
            return new LiveOrderRecoveryRun(0, 0, 0, 0, 1,
                    false, false, false);
        } finally {
            running.set(false);
        }
    }

    public Readiness getReadiness() {
        return readiness.get();
    }

    public boolean isRecoveryComplete() {
        return readiness.get() == Readiness.READY;
    }

    private void markUnknown(OmsOrderDO candidate, RuntimeException failure) {
        String type = failure.getClass().getSimpleName();
        String externalStatus = "RECONCILE_ERROR:" + (type.isBlank() ? "RuntimeException" : type);
        if (externalStatus.length() > 50) externalStatus = externalStatus.substring(0, 50);
        try {
            persistenceService.markUnknown(candidate.getOrderId(), externalStatus,
                    System.currentTimeMillis());
        } catch (RuntimeException persistenceFailure) {
            log.error("Failed to persist UNKNOWN after reconciliation failure: orderId={}",
                    candidate.getOrderId(), persistenceFailure);
        }
    }

    private void count(Trigger trigger, String outcome) {
        meterRegistry.counter("crypto_live_order_reconciliation_total",
                "trigger", trigger.name().toLowerCase(), "outcome", outcome).increment();
    }

    private void block(String reason, String message) {
        setReadiness(Readiness.BLOCKED);
        if (killSwitch.getMode() == KillSwitch.Mode.HALT) return;
        try {
            killSwitch.activate(KillSwitch.Mode.HALT, reason, "LIVE_RECOVERY");
        } catch (RuntimeException persistenceFailure) {
            // KillSwitch itself has already moved local state to HALT on persistence failure.
            log.error("{}; durable HALT persistence also failed", message, persistenceFailure);
        }
    }

    private void setReadiness(Readiness target) {
        readiness.set(target);
        if (target == Readiness.READY) recoveryGate.markReady();
        else recoveryGate.markBlocked();
        readyGauge.set(target == Readiness.READY ? 1 : 0);
    }
}
