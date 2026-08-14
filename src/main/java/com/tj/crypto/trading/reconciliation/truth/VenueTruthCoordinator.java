package com.tj.crypto.trading.reconciliation.truth;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.risk.KillSwitch;
import com.tj.crypto.trading.reconciliation.LiveOrderRecoveryGate;
import com.tj.crypto.trading.venue.PrivateVenueGateway;
import com.tj.crypto.trading.venue.PrivateVenueGatewayRegistry;
import com.tj.crypto.trading.venue.VenueAccountSnapshot;
import com.tj.crypto.trading.venue.VenueBalance;
import com.tj.crypto.trading.venue.VenuePosition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Fail-closed account-wide venue truth scan which runs after known-order recovery. */
@Slf4j
@Component
public class VenueTruthCoordinator {
    private static final int DISCOVERY_LIMIT = 10_000;
    private static final long FILL_LOOKBACK_MS = 7L * 24 * 60 * 60 * 1_000;

    private final PrivateVenueGatewayRegistry gateways;
    private final InternalVenueTruthSource internalTruth;
    private final CompositeLiveOrderRecoveryGate recoveryGate;
    private final KillSwitch killSwitch;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<VenueTruthRun> lastRun = new AtomicReference<>();

    public VenueTruthCoordinator(PrivateVenueGatewayRegistry gateways,
                                 InternalVenueTruthSource internalTruth,
                                 CompositeLiveOrderRecoveryGate recoveryGate,
                                 KillSwitch killSwitch) {
        this.gateways = gateways;
        this.internalTruth = internalTruth;
        this.recoveryGate = recoveryGate;
        this.killSwitch = killSwitch;
    }

    @EventListener(ApplicationReadyEvent.class)
    @org.springframework.core.annotation.Order(Ordered.HIGHEST_PRECEDENCE + 200)
    public void recoverOnStartup() {
        reconcile(VenueTruthRun.Trigger.STARTUP);
    }

    @Scheduled(fixedDelayString = "${crypto.private-trading.reconciliation-interval-ms:60000}")
    public void periodicReconciliation() {
        reconcile(VenueTruthRun.Trigger.PERIODIC);
    }

    public VenueTruthRun reconcile(VenueTruthRun.Trigger trigger) {
        long now = System.currentTimeMillis();
        if (!running.compareAndSet(false, true)) {
            return blocked(trigger, now, List.of(), List.of(new VenueTruthDifference(
                    VenueTruthDifference.Code.CONCURRENT_RUN_SKIPPED, null, null, "",
                    "A venue truth run is already in progress")));
        }
        recoveryGate.markTruthPending();
        try {
            List<VenueTruthDifference> differences = new ArrayList<>();
            if (recoveryGate.internalState() != LiveOrderRecoveryGate.State.READY) {
                differences.add(new VenueTruthDifference(
                        VenueTruthDifference.Code.INTERNAL_RECOVERY_NOT_READY, null, null, "",
                        "Known-order recovery must be READY before account-wide truth promotion"));
                return blocked(trigger, now, List.of(), differences);
            }

            List<PrivateVenueGateway> configured = new ArrayList<>();
            for (PrivateVenueGateway gateway : gateways.all()) {
                try {
                    if (gateway.configured()) configured.add(gateway);
                } catch (RuntimeException e) {
                    differences.add(queryFailure(gateway.exchange(), null, "configured", e));
                }
            }
            List<Exchange> exchanges = configured.stream().map(PrivateVenueGateway::exchange).toList();
            if (configured.isEmpty()) {
                differences.add(new VenueTruthDifference(
                        VenueTruthDifference.Code.NO_CONFIGURED_GATEWAY, null, null, "",
                        "No configured private venue is available for account-wide truth discovery"));
                return blocked(trigger, now, exchanges, differences);
            }

            VenueTruthRequest request = new VenueTruthRequest(Math.max(0, now - FILL_LOOKBACK_MS),
                    DISCOVERY_LIMIT);
            for (PrivateVenueGateway gateway : configured) {
                reconcileGateway(gateway, request, differences);
            }
            if (!differences.isEmpty()) return blocked(trigger, now, exchanges, differences);

            recoveryGate.markTruthReady();
            VenueTruthRun run = new VenueTruthRun(trigger, now, recoveryGate.state(), exchanges, List.of());
            lastRun.set(run);
            return run;
        } catch (RuntimeException e) {
            return blocked(trigger, now, List.of(), List.of(queryFailure(null, null, "scan", e)));
        } finally {
            running.set(false);
        }
    }

    public VenueTruthRun lastRun() {
        return lastRun.get();
    }

    private void reconcileGateway(PrivateVenueGateway gateway, VenueTruthRequest request,
                                  List<VenueTruthDifference> differences) {
        Exchange exchange = gateway.exchange();
        VenueTruthCapabilities venue;
        VenueTruthCapabilities internal;
        try {
            venue = requireCapabilities(gateway.truthCapabilities(), "venue");
        } catch (RuntimeException e) {
            differences.add(queryFailure(exchange, null, "venue capabilities", e));
            return;
        }
        try {
            internal = requireCapabilities(internalTruth.capabilities(exchange), "internal");
        } catch (RuntimeException e) {
            differences.add(internalQueryFailure(exchange, null, "internal capabilities", e));
            return;
        }
        for (VenueTruthCapability capability : VenueTruthCapability.values()) {
            if (!venue.supports(capability)) {
                differences.add(new VenueTruthDifference(
                        VenueTruthDifference.Code.VENUE_CAPABILITY_UNSUPPORTED, exchange, capability,
                        "", venue.detail(capability)));
            }
            if (!internal.supports(capability)) {
                differences.add(new VenueTruthDifference(
                        VenueTruthDifference.Code.INTERNAL_CAPABILITY_UNSUPPORTED, exchange, capability,
                        "", internal.detail(capability)));
            }
        }
        if (!allSupported(venue) || !allSupported(internal)) return;

        List<VenueTruthOrder> venueOrders = venueOrders(gateway, exchange, request, differences);
        List<VenueTruthOrder> internalOrders = internalOrders(exchange, request, differences);
        if (venueOrders != null && internalOrders != null) {
            compareOrders(exchange, venueOrders, internalOrders, differences);
        }
        List<VenueTruthFill> venueFills = venueFills(gateway, exchange, request, differences);
        List<VenueTruthFill> internalFills = internalFills(exchange, request, differences);
        if (venueFills != null && internalFills != null) {
            compareFills(exchange, venueFills, internalFills, differences);
        }
        VenueAccountSnapshot venueAccount = venueAccount(gateway, exchange, differences);
        VenueAccountSnapshot internalAccount = internalAccount(exchange, differences);
        if (venueAccount != null && internalAccount != null) {
            compareBalances(exchange, venueAccount.balances(), internalAccount.balances(), differences);
            comparePositions(exchange, venueAccount.positions(), internalAccount.positions(), differences);
        }
    }

    private List<VenueTruthOrder> venueOrders(PrivateVenueGateway gateway, Exchange exchange,
                                             VenueTruthRequest request,
                                             List<VenueTruthDifference> differences) {
        try {
            return requireList(gateway.activeOrders(request), "venue active orders");
        } catch (RuntimeException e) {
            differences.add(queryFailure(exchange, VenueTruthCapability.ACTIVE_ORDERS,
                    "active orders", e));
            return null;
        }
    }

    private List<VenueTruthOrder> internalOrders(Exchange exchange, VenueTruthRequest request,
                                                List<VenueTruthDifference> differences) {
        try {
            return requireList(internalTruth.activeOrders(exchange, request), "internal active orders");
        } catch (RuntimeException e) {
            differences.add(internalQueryFailure(exchange, VenueTruthCapability.ACTIVE_ORDERS,
                    "active orders", e));
            return null;
        }
    }

    private List<VenueTruthFill> venueFills(PrivateVenueGateway gateway, Exchange exchange,
                                           VenueTruthRequest request,
                                           List<VenueTruthDifference> differences) {
        try {
            return requireList(gateway.recentFills(request), "venue recent fills");
        } catch (RuntimeException e) {
            differences.add(queryFailure(exchange, VenueTruthCapability.RECENT_FILLS,
                    "recent fills", e));
            return null;
        }
    }

    private List<VenueTruthFill> internalFills(Exchange exchange, VenueTruthRequest request,
                                              List<VenueTruthDifference> differences) {
        try {
            return requireList(internalTruth.recentFills(exchange, request), "internal recent fills");
        } catch (RuntimeException e) {
            differences.add(internalQueryFailure(exchange, VenueTruthCapability.RECENT_FILLS,
                    "recent fills", e));
            return null;
        }
    }

    private VenueAccountSnapshot venueAccount(PrivateVenueGateway gateway, Exchange exchange,
                                              List<VenueTruthDifference> differences) {
        try {
            return requireAccount(gateway.account(), exchange, "venue");
        } catch (RuntimeException e) {
            differences.add(queryFailure(exchange, VenueTruthCapability.BALANCES,
                    "balance/position snapshot", e));
            return null;
        }
    }

    private VenueAccountSnapshot internalAccount(Exchange exchange,
                                                 List<VenueTruthDifference> differences) {
        try {
            return requireAccount(internalTruth.account(exchange), exchange, "internal");
        } catch (RuntimeException e) {
            differences.add(internalQueryFailure(exchange, VenueTruthCapability.BALANCES,
                    "balance/position snapshot", e));
            return null;
        }
    }

    private void compareOrders(Exchange exchange, List<VenueTruthOrder> venue,
                               List<VenueTruthOrder> internal,
                               List<VenueTruthDifference> differences) {
        List<VenueTruthOrder> unmatchedInternal = new ArrayList<>(internal);
        for (VenueTruthOrder venueOrder : venue) {
            VenueTruthOrder match = unmatchedInternal.stream()
                    .filter(candidate -> sameOrderIdentity(venueOrder, candidate)).findFirst().orElse(null);
            if (match == null) {
                differences.add(diff(VenueTruthDifference.Code.VENUE_ORPHAN_ORDER, exchange,
                        VenueTruthCapability.ACTIVE_ORDERS, venueOrder.identity(),
                        "Venue active order has no durable internal order"));
            } else {
                unmatchedInternal.remove(match);
                if (!sameOrder(venueOrder, match)) {
                    differences.add(diff(VenueTruthDifference.Code.ORDER_MISMATCH, exchange,
                            VenueTruthCapability.ACTIVE_ORDERS, venueOrder.identity(),
                            "Venue and internal active-order facts differ"));
                }
            }
        }
        for (VenueTruthOrder missing : unmatchedInternal) {
            differences.add(diff(VenueTruthDifference.Code.INTERNAL_ORDER_MISSING_AT_VENUE, exchange,
                    VenueTruthCapability.ACTIVE_ORDERS, missing.identity(),
                    "Durable internal active order is absent from the venue active-order snapshot"));
        }
    }

    private void compareFills(Exchange exchange, List<VenueTruthFill> venue,
                              List<VenueTruthFill> internal,
                              List<VenueTruthDifference> differences) {
        Map<String, VenueTruthFill> venueById = unique(venue, VenueTruthFill::exchangeTradeId, "venue fill");
        Map<String, VenueTruthFill> internalById = unique(internal, VenueTruthFill::exchangeTradeId,
                "internal fill");
        for (var entry : venueById.entrySet()) {
            VenueTruthFill known = internalById.get(entry.getKey());
            if (known == null) {
                differences.add(diff(VenueTruthDifference.Code.INTERNAL_FILL_MISSING, exchange,
                        VenueTruthCapability.RECENT_FILLS, entry.getKey(),
                        "Venue fill is missing from the durable internal projection"));
            } else if (!sameFill(entry.getValue(), known)) {
                differences.add(diff(VenueTruthDifference.Code.FILL_MISMATCH, exchange,
                        VenueTruthCapability.RECENT_FILLS, entry.getKey(),
                        "Venue and internal fill facts differ"));
            }
        }
        for (String id : internalById.keySet()) {
            if (!venueById.containsKey(id)) {
                differences.add(diff(VenueTruthDifference.Code.VENUE_FILL_MISSING, exchange,
                        VenueTruthCapability.RECENT_FILLS, id,
                        "Internal fill is absent from the bounded venue fill snapshot"));
            }
        }
    }

    private void compareBalances(Exchange exchange, List<VenueBalance> venue,
                                 List<VenueBalance> internal,
                                 List<VenueTruthDifference> differences) {
        compareMapped(exchange, VenueTruthCapability.BALANCES,
                unique(requireList(venue, "venue balances"), balance -> upper(balance.asset()), "venue balance"),
                unique(requireList(internal, "internal balances"), balance -> upper(balance.asset()),
                        "internal balance"),
                this::sameBalance, VenueTruthDifference.Code.INTERNAL_BALANCE_MISSING,
                VenueTruthDifference.Code.VENUE_BALANCE_MISSING,
                VenueTruthDifference.Code.BALANCE_MISMATCH, differences);
    }

    private void comparePositions(Exchange exchange, List<VenuePosition> venue,
                                  List<VenuePosition> internal,
                                  List<VenueTruthDifference> differences) {
        Function<VenuePosition, String> key = position ->
                VenueTruthOrder.canonicalSymbol(position.symbol()) + ':' + upper(position.side());
        compareMapped(exchange, VenueTruthCapability.POSITIONS,
                unique(requireList(venue, "venue positions"), key, "venue position"),
                unique(requireList(internal, "internal positions"), key, "internal position"),
                this::samePosition, VenueTruthDifference.Code.INTERNAL_POSITION_MISSING,
                VenueTruthDifference.Code.VENUE_POSITION_MISSING,
                VenueTruthDifference.Code.POSITION_MISMATCH, differences);
    }

    private <T> void compareMapped(Exchange exchange, VenueTruthCapability capability,
                                   Map<String, T> venue, Map<String, T> internal,
                                   java.util.function.BiPredicate<T, T> equal,
                                   VenueTruthDifference.Code missingInternal,
                                   VenueTruthDifference.Code missingVenue,
                                   VenueTruthDifference.Code mismatch,
                                   List<VenueTruthDifference> differences) {
        for (var entry : venue.entrySet()) {
            T known = internal.get(entry.getKey());
            if (known == null) differences.add(diff(missingInternal, exchange, capability,
                    entry.getKey(), "Venue fact is missing from the internal projection"));
            else if (!equal.test(entry.getValue(), known)) differences.add(diff(mismatch, exchange,
                    capability, entry.getKey(), "Venue and internal facts differ"));
        }
        for (String key : internal.keySet()) {
            if (!venue.containsKey(key)) differences.add(diff(missingVenue, exchange, capability,
                    key, "Internal fact is absent from the venue snapshot"));
        }
    }

    private VenueTruthRun blocked(VenueTruthRun.Trigger trigger, long now, List<Exchange> exchanges,
                                  List<VenueTruthDifference> differences) {
        recoveryGate.markTruthBlocked();
        // No configured private venue means no live venue risk exists to stop. Keep the live
        // opening gate BLOCKED while allowing a research-only deployment's separate paper flow.
        // Once a private venue is configured, any incomplete truth is a system-wide incident.
        if (!exchanges.isEmpty() && killSwitch.getMode() != KillSwitch.Mode.HALT) {
            try {
                killSwitch.activate(KillSwitch.Mode.HALT, "VENUE_TRUTH_NOT_CONVERGED", "VENUE_TRUTH");
            } catch (RuntimeException e) {
                log.error("Venue truth blocked and durable HALT persistence failed", e);
            }
        }
        VenueTruthRun run = new VenueTruthRun(trigger, now, recoveryGate.state(), exchanges, differences);
        lastRun.set(run);
        return run;
    }

    private boolean allSupported(VenueTruthCapabilities capabilities) {
        for (VenueTruthCapability capability : VenueTruthCapability.values()) {
            if (!capabilities.supports(capability)) return false;
        }
        return true;
    }

    private VenueTruthCapabilities requireCapabilities(VenueTruthCapabilities value, String owner) {
        if (value == null) throw new IllegalStateException(owner + " capabilities are null");
        return value;
    }

    private VenueAccountSnapshot requireAccount(VenueAccountSnapshot value, Exchange exchange,
                                                String owner) {
        if (value == null) throw new IllegalStateException(owner + " account snapshot is null");
        if (value.exchange() == null || !exchange.name().equalsIgnoreCase(value.exchange())) {
            throw new IllegalStateException(owner + " account snapshot exchange mismatch");
        }
        if (value.eventTimeMs() <= 0) throw new IllegalStateException(owner + " account timestamp invalid");
        requireList(value.balances(), owner + " balances");
        requireList(value.positions(), owner + " positions");
        return value;
    }

    private <T> List<T> requireList(List<T> value, String name) {
        if (value == null) throw new IllegalStateException(name + " are null");
        if (value.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalStateException(name + " contain null");
        }
        return value;
    }

    private <T> Map<String, T> unique(List<T> values, Function<T, String> key,
                                      String description) {
        Map<String, T> result = new HashMap<>();
        for (T value : values) {
            String identity = key.apply(value);
            if (identity == null || identity.isBlank() || result.put(identity, value) != null) {
                throw new IllegalStateException("Duplicate or blank " + description + " identity");
            }
        }
        return result;
    }

    private boolean sameOrderIdentity(VenueTruthOrder left, VenueTruthOrder right) {
        return left.venueOrderId() != null && left.venueOrderId().equals(right.venueOrderId())
                || left.clientOrderId() != null && left.clientOrderId().equals(right.clientOrderId());
    }

    private boolean sameOrder(VenueTruthOrder left, VenueTruthOrder right) {
        return left.marketType() == right.marketType() && left.symbol().equals(right.symbol())
                && left.state() == right.state()
                && number(left.originalQuantity(), right.originalQuantity())
                && number(left.cumulativeFilledQuantity(), right.cumulativeFilledQuantity());
    }

    private boolean sameFill(VenueTruthFill left, VenueTruthFill right) {
        return left.marketType() == right.marketType() && left.symbol().equals(right.symbol())
                && number(left.quantity(), right.quantity()) && number(left.price(), right.price());
    }

    private boolean sameBalance(VenueBalance left, VenueBalance right) {
        return number(left.total(), right.total()) && number(left.available(), right.available())
                && number(left.locked(), right.locked());
    }

    private boolean samePosition(VenuePosition left, VenuePosition right) {
        return number(left.quantity(), right.quantity()) && number(left.entryPrice(), right.entryPrice())
                && number(left.markPrice(), right.markPrice())
                && number(left.unrealizedPnl(), right.unrealizedPnl())
                && left.leverage() == right.leverage()
                && upper(left.marginMode()).equals(upper(right.marginMode()));
    }

    private boolean number(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private String upper(String value) {
        if (value == null || value.isBlank()) throw new IllegalStateException("Truth identity is blank");
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private VenueTruthDifference queryFailure(Exchange exchange, VenueTruthCapability capability,
                                              String operation, RuntimeException failure) {
        return diff(VenueTruthDifference.Code.VENUE_QUERY_FAILED, exchange, capability, operation,
                operation + " failed: " + failure.getClass().getSimpleName());
    }

    private VenueTruthDifference internalQueryFailure(Exchange exchange,
                                                      VenueTruthCapability capability,
                                                      String operation,
                                                      RuntimeException failure) {
        return diff(VenueTruthDifference.Code.INTERNAL_QUERY_FAILED, exchange, capability, operation,
                operation + " failed: " + failure.getClass().getSimpleName());
    }

    private VenueTruthDifference diff(VenueTruthDifference.Code code, Exchange exchange,
                                      VenueTruthCapability capability, String identity, String message) {
        return new VenueTruthDifference(code, exchange, capability, identity, message);
    }
}
