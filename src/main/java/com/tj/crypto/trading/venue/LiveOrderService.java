package com.tj.crypto.trading.venue;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.execution.OrderStateMachine;
import com.tj.crypto.execution.journal.OmsFillMetadata;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderEvent;
import com.tj.crypto.execution.model.OrderRejectReason;
import com.tj.crypto.execution.model.OrderStatus;
import com.tj.crypto.storage.entity.OmsOrderDO;
import com.tj.crypto.storage.mapper.OmsOrderMapper;
import com.tj.crypto.storage.service.OmsPersistenceService;
import com.tj.crypto.trading.instrument.InstrumentMetadata;
import com.tj.crypto.trading.instrument.InstrumentMetadataService;
import com.tj.crypto.trading.paper.OmsOrderDomainConverter;
import com.tj.crypto.trading.reconciliation.LiveOrderRecoveryGate;
import com.tj.crypto.trading.venue.fencing.ExecutionWriterLeaseService;
import com.tj.crypto.trading.venue.riskreservation.LiveRiskReservationBudget;
import com.tj.crypto.trading.venue.riskreservation.LiveRiskReservationService;
import com.tj.crypto.trading.venue.stream.VenueOrderUpdate;
import com.tj.crypto.trading.venue.stream.VenuePrivateEventProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Durable live command orchestration. Network calls never sit inside a database transaction. */
@Service
@RequiredArgsConstructor
public class LiveOrderService {
    private final LiveTradingWriteGuard writeGuard;
    private final PrivateVenueGatewayRegistry gatewayRegistry;
    private final InstrumentMetadataService metadataService;
    private final VenuePreTradeRiskService riskService;
    private final OmsOrderMapper orderMapper;
    private final OmsPersistenceService persistenceService;
    private final VenuePrivateEventProcessor privateEventProcessor;
    private final LiveRiskReservationService riskReservationService;
    private ExecutionWriterLeaseService writerLeaseService;
    private LiveOrderRecoveryGate recoveryGate;

    /** Required in the Spring runtime; intentionally absent from direct unit-test construction. */
    @Autowired
    void setWriterLeaseService(ExecutionWriterLeaseService writerLeaseService) {
        this.writerLeaseService = writerLeaseService;
    }

    /** Required in the Spring runtime; direct unit tests remain side-effect-free without it. */
    @Autowired
    void setRecoveryGate(LiveOrderRecoveryGate recoveryGate) {
        this.recoveryGate = recoveryGate;
    }

    public synchronized Order place(LiveOrderRequest request) {
        requirePlaceAuthorization(request.exchange());
        long now = System.currentTimeMillis();
        InstrumentMetadata metadata = metadataService.require(request.exchange(), request.marketType(),
                request.symbol(), now);
        BigDecimal quantity = metadata.alignQuantity(request.quantity());
        BigDecimal reference = metadata.alignPrice(request.orderType() == com.tj.crypto.execution.model.OrderType.LIMIT
                ? request.limitPrice() : request.referencePrice());
        metadata.validate(reference, quantity, request.leverage());
        String clientOrderId = request.clientOrderId() == null || request.clientOrderId().isBlank()
                ? compactId() : request.clientOrderId().trim();
        OmsOrderDO duplicate = orderMapper.selectByClientOrderId(clientOrderId);
        if (duplicate != null) return verifyReplay(request, quantity, reference, duplicate);

        PrivateVenueGateway gateway = gatewayRegistry.require(request.exchange());
        Instrument instrument = metadata.instrument();
        VenueOrderCommand command = new VenueOrderCommand(request.accountId(), clientOrderId,
                instrument, request.side(), request.positionSide(), request.orderType(), quantity,
                request.orderType() == com.tj.crypto.execution.model.OrderType.LIMIT ? reference : null,
                request.reduceOnly(), request.leverage(), request.marginMode());
        LiveRiskReservationBudget riskBudget = riskService.validateAndCreateBudget(
                command, reference, gateway.account(instrument),
                request.reduceOnly() ? List.of() : pendingExposures(request));

        Order order = new Order(UUID.randomUUID().toString(), clientOrderId, instrument,
                request.side() == com.tj.crypto.common.domain.TradeSide.BUY ? OrderSide.LONG : OrderSide.SHORT,
                request.orderType(), quantity, reference, BigDecimal.ZERO, null,
                OrderStatus.CREATED, OrderRejectReason.NONE, now, 0, 0, 0,
                request.strategyId(), request.side(), request.positionSide(), request.reduceOnly());
        OmsFillMetadata journalMetadata = metadata(request, reference);
        try {
            OrderEvent created = OrderEvent.created(order.orderId(), now);
            riskReservationService.reserveAndRecord(
                    order, created, journalMetadata, riskBudget);
        } catch (DuplicateKeyException duplicateClientOrderId) {
            OmsOrderDO claimed = orderMapper.selectByClientOrderId(clientOrderId);
            if (claimed == null) throw duplicateClientOrderId;
            return verifyReplay(request, quantity, reference, claimed);
        }
        OrderEvent submitted = OrderEvent.submitted(order.orderId(), now);
        order = OrderStateMachine.transition(order, submitted);
        persistenceService.recordVenue(order, submitted, null, journalMetadata, "LIVE");

        try {
            // Close the authorization/lease TOCTOU window as far as the local process can. The
            // venue clientOrderId remains the external idempotency key; a database fencing token
            // cannot be enforced by an exchange which does not accept it.
            requirePlaceAuthorization(request.exchange());
            VenueOrderSnapshot response = gateway.place(command);
            persistenceService.attachVenue(order.orderId(), response.venueOrderId(), response.rawStatus());
            if (response.state() == VenueOrderState.ACCEPTED) {
                OrderEvent acknowledged = OrderEvent.acknowledged(order.orderId(), response.eventTimeMs());
                order = OrderStateMachine.transition(order, acknowledged);
                persistenceService.recordVenue(order, acknowledged, null, journalMetadata, "LIVE");
            } else {
                reconcileSnapshot(order, response);
                OmsOrderDO latest = orderMapper.selectById(order.orderId());
                order = latest == null ? order : OmsOrderDomainConverter.toDomain(latest);
            }
            return order;
        } catch (VenueApiException e) {
            if (e.httpStatus() == 0 || e.httpStatus() >= 500) {
                persistenceService.markUnknown(order.orderId(), e.venueCode(), System.currentTimeMillis());
                return OmsOrderDomainConverter.toDomain(orderMapper.selectById(order.orderId()));
            }
            OrderEvent rejected = OrderEvent.rejected(order.orderId(), System.currentTimeMillis(),
                    OrderRejectReason.INVALID_ORDER);
            Order result = OrderStateMachine.transition(order, rejected);
            persistenceService.recordVenue(result, rejected, null, journalMetadata, "LIVE");
            return result;
        }
    }

    public Order cancel(String orderId) {
        OmsOrderDO stored = orderMapper.selectById(orderId);
        if (stored == null) throw new IllegalArgumentException("Unknown order: " + orderId);
        Order order = OmsOrderDomainConverter.toDomain(stored);
        boolean firstRequest = order.status() == OrderStatus.ACKNOWLEDGED
                || order.status() == OrderStatus.PARTIALLY_FILLED;
        boolean retryRequest = order.status() == OrderStatus.UNKNOWN
                || order.status() == OrderStatus.CANCEL_REQUESTED;
        if (!firstRequest && !retryRequest) {
            throw new IllegalStateException("Live order cannot be cancelled from " + order.status());
        }
        writeGuard.requireCancelEnabled(order.instrument().exchange());
        requireWriterLease();
        if (firstRequest || order.status() == OrderStatus.UNKNOWN) {
            OrderEvent requested = OrderEvent.cancelRequested(orderId, System.currentTimeMillis());
            order = OrderStateMachine.transition(order, requested);
            persistenceService.recordVenue(order, requested, null, metadata(stored), "LIVE");
        }
        try {
            writeGuard.requireCancelEnabled(order.instrument().exchange());
            requireWriterLease();
            VenueOrderSnapshot response = gatewayRegistry.require(order.instrument().exchange())
                    .cancel(reference(order, stored));
            persistenceService.attachVenue(orderId, response.venueOrderId(), response.rawStatus());
            if (response.state() == VenueOrderState.CANCELLED) {
                OrderEvent cancelled = OrderEvent.cancelled(orderId, response.eventTimeMs());
                Order latest = OmsOrderDomainConverter.toDomain(orderMapper.selectById(orderId));
                Order result = OrderStateMachine.transition(latest, cancelled);
                persistenceService.recordVenue(result, cancelled, null, metadata(stored), "LIVE");
                return result;
            }
            return order;
        } catch (VenueApiException e) {
            persistenceService.markUnknown(orderId, e.venueCode(), System.currentTimeMillis());
            return OmsOrderDomainConverter.toDomain(orderMapper.selectById(orderId));
        }
    }

    public Order reconcile(String orderId) {
        OmsOrderDO stored = orderMapper.selectById(orderId);
        if (stored == null) throw new IllegalArgumentException("Unknown order: " + orderId);
        if (!"LIVE".equalsIgnoreCase(stored.getOrderSource())) {
            throw new IllegalArgumentException("Only LIVE orders can be reconciled with a venue");
        }
        Order order = OmsOrderDomainConverter.toDomain(stored);
        // A terminal private-stream update may win the race after the recovery scan.
        if (!order.status().isActive()) return order;
        PrivateVenueGateway gateway = gatewayRegistry.require(order.instrument().exchange());
        if (!gateway.configured()) {
            throw new IllegalStateException("Private venue is not configured for reconciliation: "
                    + order.instrument().exchange());
        }
        // Query is deliberately read-only and remains available while live writes are disabled.
        VenueOrderSnapshot snapshot = gateway.query(reference(order, stored));
        reconcileSnapshot(order, snapshot);
        return OmsOrderDomainConverter.toDomain(orderMapper.selectById(orderId));
    }

    private void reconcileSnapshot(Order order, VenueOrderSnapshot snapshot) {
        String fingerprint = order.instrument().exchange().name() + ":REST:" + order.orderId()
                + ":" + snapshot.eventTimeMs() + ":" + snapshot.cumulativeFilledQuantity()
                + ":" + snapshot.rawStatus();
        String clientOrderId = present(snapshot.clientOrderId())
                ? snapshot.clientOrderId() : order.clientOrderId();
        privateEventProcessor.process(new VenueOrderUpdate(order.instrument().exchange(), fingerprint,
                null, snapshot.eventTimeMs(), order.instrument(), snapshot.venueOrderId(),
                clientOrderId, null, snapshot.rawStatus(), snapshot.state(),
                snapshot.originalQuantity(), snapshot.cumulativeFilledQuantity(), BigDecimal.ZERO,
                snapshot.averageFillPrice(), snapshot.averageFillPrice(), BigDecimal.ZERO,
                null, null));
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private VenueCancelCommand reference(Order order, OmsOrderDO stored) {
        return new VenueCancelCommand(order.instrument(), stored.getVenueOrderId(), order.clientOrderId());
    }

    private OmsFillMetadata metadata(LiveOrderRequest request, BigDecimal alignedReference) {
        return new OmsFillMetadata(request.accountId(), request.correlationId(), null,
                BigDecimal.ZERO, null, null, alignedReference, alignedReference,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                request.leverage(), request.marginMode());
    }

    private OmsFillMetadata metadata(OmsOrderDO order) {
        return new OmsFillMetadata(order.getAccountId(), order.getCorrelationId(), null,
                BigDecimal.ZERO, null, null, order.getPrice(), order.getPrice(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                order.getLeverage() == null ? 1 : order.getLeverage(), order.getMarginMode());
    }

    private Order verifyReplay(LiveOrderRequest request, BigDecimal quantity,
                               BigDecimal reference, OmsOrderDO existing) {
        boolean same = "LIVE".equals(existing.getOrderSource())
                && request.exchange().name().equalsIgnoreCase(existing.getExchange())
                && request.marketType().name().equalsIgnoreCase(existing.getMarketType())
                && request.symbol().replace("-", "").equalsIgnoreCase(existing.getSymbol())
                && request.side().name().equals(existing.getTradeSide())
                && request.orderType().name().equals(existing.getOrderType())
                && quantity.compareTo(existing.getQuantity()) == 0
                && reference.compareTo(existing.getPrice()) == 0
                && request.reduceOnly() == Boolean.TRUE.equals(existing.getReduceOnly())
                && request.leverage() == (existing.getLeverage() == null ? 1 : existing.getLeverage());
        if (!same) throw new IllegalArgumentException("clientOrderId was used for a different live order");
        return OmsOrderDomainConverter.toDomain(existing);
    }

    private String compactId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    private void requireWriterLease() {
        // Spring requires the setter dependency. Directly-constructed unit tests have no external
        // venue side effects because all gateways are fakes/mocks.
        if (writerLeaseService != null) writerLeaseService.requireOwnership();
    }

    private void requirePlaceAuthorization(com.tj.crypto.common.domain.Exchange exchange) {
        if (recoveryGate != null) recoveryGate.requireReadyForOpeningRisk();
        writeGuard.requireWriteEnabled(exchange);
        requireWriterLease();
    }

    private List<VenuePendingExposure> pendingExposures(LiveOrderRequest request) {
        List<VenuePendingExposure> pending = new ArrayList<>();
        for (OmsOrderDO active : orderMapper.selectActiveByAccount(request.accountId())) {
            if (!"LIVE".equalsIgnoreCase(active.getOrderSource())) continue;
            if ("UNKNOWN".equalsIgnoreCase(active.getStatus())) {
                throw new IllegalStateException(
                        "Cannot add live risk while an order outcome is UNKNOWN");
            }
            if (!request.exchange().name().equalsIgnoreCase(active.getExchange())) continue;
            try {
                BigDecimal quantity = active.getQuantity();
                BigDecimal filled = active.getFilledQuantity() == null
                        ? BigDecimal.ZERO : active.getFilledQuantity();
                BigDecimal remaining = quantity.subtract(filled);
                if (remaining.signum() <= 0) continue;
                pending.add(new VenuePendingExposure(request.exchange(),
                        com.tj.crypto.common.domain.MarketType.valueOf(active.getMarketType()),
                        active.getSymbol(), remaining, active.getPrice(),
                        Boolean.TRUE.equals(active.getReduceOnly())));
            } catch (RuntimeException e) {
                throw new IllegalStateException(
                        "Active live order cannot be valued for pre-trade risk", e);
            }
        }
        return List.copyOf(pending);
    }
}
