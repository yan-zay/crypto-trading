package com.tj.crypto.trading.venue.stream;

import com.tj.crypto.execution.OrderStateMachine;
import com.tj.crypto.execution.journal.OmsFillMetadata;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderEvent;
import com.tj.crypto.execution.model.OrderRejectReason;
import com.tj.crypto.execution.model.OrderStatus;
import com.tj.crypto.storage.entity.OmsOrderDO;
import com.tj.crypto.storage.mapper.OmsOrderMapper;
import com.tj.crypto.storage.service.OmsPersistenceService;
import com.tj.crypto.trading.paper.OmsOrderDomainConverter;
import com.tj.crypto.trading.venue.VenueOrderState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/** Applies normalized private events to OMS with cumulative-fill and external-id idempotency. */
@Slf4j
@Service
@RequiredArgsConstructor
public class VenuePrivateEventProcessor {
    private final OmsOrderMapper orderMapper;
    private final OmsPersistenceService persistenceService;

    @Transactional
    public void process(VenuePrivateEvent event) {
        if (event instanceof VenueOrderUpdate update) processOrder(update);
        else if (event instanceof VenueAccountRefresh refresh) {
            log.debug("Private account refresh received: exchange={}, channel={}",
                    refresh.exchange(), refresh.channel());
        }
    }

    public void processOrder(VenueOrderUpdate update) {
        OmsOrderDO lookup = find(update);
        if (lookup == null) {
            log.warn("Ignoring private update for unknown order: exchange={}, clientOrderId={}, venueOrderId={}",
                    update.exchange(), update.clientOrderId(), update.venueOrderId());
            return;
        }
        OmsOrderDO stored = orderMapper.selectByIdForUpdate(lookup.getOrderId());
        Order order = OmsOrderDomainConverter.toDomain(stored);
        if (!order.status().isActive()) return;

        BigDecimal cumulative = update.cumulativeFilledQuantity();
        if (cumulative.compareTo(order.filledQuantity()) < 0
                || cumulative.compareTo(order.quantity()) > 0) {
            persistenceService.markUnknown(order.orderId(), "INVALID_CUMULATIVE_FILL", update.eventTimeMs());
            return;
        }

        if (cumulative.compareTo(order.filledQuantity()) > 0) {
            order = ensureAcknowledged(order, stored, update.eventTimeMs());
            BigDecimal delta = cumulative.subtract(order.filledQuantity());
            BigDecimal fillPrice = effectiveFillPrice(update, order);
            OrderEvent fill = cumulative.compareTo(order.quantity()) == 0
                    ? OrderEvent.filled(order.orderId(), update.eventTimeMs(), fillPrice, delta)
                    : OrderEvent.partiallyFilled(order.orderId(), update.eventTimeMs(), fillPrice, delta);
            Order transitioned = OrderStateMachine.transition(order, fill);
            String fillExternalId = update.state() == VenueOrderState.CANCELLED
                    ? update.externalEventId() + ":FILL" : update.externalEventId();
            persistenceService.recordExternal(transitioned, fill, null, metadata(stored, update),
                    "LIVE", fillExternalId, update.payloadChecksum(),
                    update.venueOrderId(), update.rawStatus());
            if (update.state() == VenueOrderState.CANCELLED && transitioned.status().isActive()) {
                VenueOrderUpdate stateUpdate = new VenueOrderUpdate(update.exchange(),
                        update.externalEventId() + ":STATE", update.payloadChecksum(),
                        update.eventTimeMs(), update.instrument(), update.venueOrderId(),
                        update.clientOrderId(), null, update.rawStatus(), VenueOrderState.CANCELLED,
                        update.originalQuantity(), cumulative, BigDecimal.ZERO, null,
                        update.averageFillPrice(), BigDecimal.ZERO, update.feeCurrency(), null);
                recordCancelled(transitioned, orderMapper.selectByIdForUpdate(order.orderId()), stateUpdate);
            }
            return;
        }

        switch (update.state()) {
            case ACCEPTED -> recordAcknowledged(order, stored, update);
            case CANCELLED -> recordCancelled(order, stored, update);
            case REJECTED -> recordRejected(order, stored, update);
            case EXPIRED -> recordExpired(order, stored, update);
            case CANCEL_PENDING -> ensureCancelRequested(order, stored, update.eventTimeMs());
            case UNKNOWN -> persistenceService.markUnknown(order.orderId(), update.rawStatus(), update.eventTimeMs());
            case PARTIALLY_FILLED, FILLED -> {
                // A repeated cumulative update has already been applied.
            }
        }
    }

    private Order ensureAcknowledged(Order order, OmsOrderDO stored, long eventTime) {
        if (order.status() == OrderStatus.CREATED) {
            OrderEvent submitted = OrderEvent.submitted(order.orderId(), eventTime);
            order = OrderStateMachine.transition(order, submitted);
            persistenceService.recordVenue(order, submitted, null, metadata(stored, null), "LIVE");
        }
        if (order.status() == OrderStatus.SUBMITTED || order.status() == OrderStatus.UNKNOWN) {
            OrderEvent acknowledged = OrderEvent.acknowledged(order.orderId(), eventTime);
            order = OrderStateMachine.transition(order, acknowledged);
            persistenceService.recordVenue(order, acknowledged, null, metadata(stored, null), "LIVE");
        }
        return order;
    }

    private void recordAcknowledged(Order order, OmsOrderDO stored, VenueOrderUpdate update) {
        if (order.status() != OrderStatus.SUBMITTED && order.status() != OrderStatus.CREATED
                && order.status() != OrderStatus.UNKNOWN) return;
        Order ready = order;
        if (ready.status() == OrderStatus.CREATED) {
            OrderEvent submitted = OrderEvent.submitted(ready.orderId(), update.eventTimeMs());
            ready = OrderStateMachine.transition(ready, submitted);
            persistenceService.recordVenue(ready, submitted, null, metadata(stored, null), "LIVE");
        }
        OrderEvent acknowledged = OrderEvent.acknowledged(ready.orderId(), update.eventTimeMs());
        ready = OrderStateMachine.transition(ready, acknowledged);
        persistenceService.recordExternal(ready, acknowledged, null, metadata(stored, update),
                "LIVE", update.externalEventId(), update.payloadChecksum(),
                update.venueOrderId(), update.rawStatus());
    }

    private void recordCancelled(Order order, OmsOrderDO stored, VenueOrderUpdate update) {
        Order cancelling = ensureAcknowledged(order, stored, update.eventTimeMs());
        cancelling = ensureCancelRequested(cancelling, stored, update.eventTimeMs());
        if (cancelling.status() != OrderStatus.CANCEL_REQUESTED) return;
        OrderEvent cancelled = OrderEvent.cancelled(cancelling.orderId(), update.eventTimeMs());
        Order result = OrderStateMachine.transition(cancelling, cancelled);
        persistenceService.recordExternal(result, cancelled, null, metadata(stored, update),
                "LIVE", update.externalEventId(), update.payloadChecksum(),
                update.venueOrderId(), update.rawStatus());
    }

    private void recordRejected(Order order, OmsOrderDO stored, VenueOrderUpdate update) {
        if (order.status() != OrderStatus.CREATED && order.status() != OrderStatus.SUBMITTED
                && order.status() != OrderStatus.UNKNOWN
                && order.status() != OrderStatus.ACKNOWLEDGED
                && order.status() != OrderStatus.PARTIALLY_FILLED) return;
        if (order.status() == OrderStatus.CREATED) {
            OrderEvent submitted = OrderEvent.submitted(order.orderId(), update.eventTimeMs());
            order = OrderStateMachine.transition(order, submitted);
            persistenceService.recordVenue(order, submitted, null, metadata(stored, null), "LIVE");
        }
        OrderEvent rejected = OrderEvent.rejected(order.orderId(), update.eventTimeMs(),
                OrderRejectReason.INVALID_ORDER);
        Order result = OrderStateMachine.transition(order, rejected);
        persistenceService.recordExternal(result, rejected, null, metadata(stored, update),
                "LIVE", update.externalEventId(), update.payloadChecksum(),
                update.venueOrderId(), update.rawStatus());
    }

    private void recordExpired(Order order, OmsOrderDO stored, VenueOrderUpdate update) {
        Order ready = ensureAcknowledged(order, stored, update.eventTimeMs());
        if (ready.status() != OrderStatus.ACKNOWLEDGED
                && ready.status() != OrderStatus.PARTIALLY_FILLED) return;
        OrderEvent expired = OrderEvent.expired(ready.orderId(), update.eventTimeMs());
        Order result = OrderStateMachine.transition(ready, expired);
        persistenceService.recordExternal(result, expired, null, metadata(stored, update),
                "LIVE", update.externalEventId(), update.payloadChecksum(),
                update.venueOrderId(), update.rawStatus());
    }

    private Order ensureCancelRequested(Order order, OmsOrderDO stored, long eventTime) {
        if (order.status() != OrderStatus.ACKNOWLEDGED
                && order.status() != OrderStatus.PARTIALLY_FILLED) return order;
        OrderEvent requested = OrderEvent.cancelRequested(order.orderId(), eventTime);
        Order result = OrderStateMachine.transition(order, requested);
        persistenceService.recordVenue(result, requested, null, metadata(stored, null), "LIVE");
        return result;
    }

    private OmsOrderDO find(VenueOrderUpdate update) {
        OmsOrderDO order = update.clientOrderId() == null ? null
                : orderMapper.selectByClientOrderId(update.clientOrderId());
        if (order == null && update.venueOrderId() != null) {
            order = orderMapper.selectByVenueOrderId(update.exchange().name(), update.venueOrderId());
        }
        return order;
    }

    private BigDecimal effectiveFillPrice(VenueOrderUpdate update, Order order) {
        if (update.lastFillPrice() != null && update.lastFillPrice().signum() > 0) return update.lastFillPrice();
        if (update.averageFillPrice() != null && update.averageFillPrice().signum() > 0) return update.averageFillPrice();
        if (order.price() != null && order.price().signum() > 0) return order.price();
        throw new IllegalArgumentException("Venue fill has no usable price");
    }

    private OmsFillMetadata metadata(OmsOrderDO order, VenueOrderUpdate update) {
        return new OmsFillMetadata(order.getAccountId(), order.getCorrelationId(),
                update == null ? null : update.exchangeTradeId(),
                update == null ? BigDecimal.ZERO : update.fee(),
                update == null ? null : update.feeCurrency(),
                update == null ? null : update.liquidityRole(), order.getPrice(), order.getPrice(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                order.getLeverage() == null ? 1 : order.getLeverage(), order.getMarginMode());
    }
}
