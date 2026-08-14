package com.tj.crypto.trading.paper;

import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.common.domain.TradeSide;
import com.tj.crypto.execution.OrderStateMachine;
import com.tj.crypto.execution.cost.ExecutionCostModel;
import com.tj.crypto.execution.cost.ExecutionCostRequest;
import com.tj.crypto.execution.cost.ExecutionFillPlan;
import com.tj.crypto.execution.cost.ExecutionSimulationProperties;
import com.tj.crypto.execution.journal.OmsFillMetadata;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderEvent;
import com.tj.crypto.execution.model.OrderRejectReason;
import com.tj.crypto.execution.model.OrderStatus;
import com.tj.crypto.reliability.outbox.OutboxService;
import com.tj.crypto.risk.KillSwitch;
import com.tj.crypto.storage.entity.OmsOrderDO;
import com.tj.crypto.storage.mapper.OmsOrderMapper;
import com.tj.crypto.storage.service.OmsPersistenceService;
import com.tj.crypto.trading.instrument.InstrumentMetadata;
import com.tj.crypto.trading.instrument.InstrumentMetadataService;
import com.tj.crypto.trading.paper.persistence.PaperAccountDO;
import com.tj.crypto.trading.paper.persistence.PaperMarkPriceDO;
import com.tj.crypto.trading.paper.persistence.PaperPositionDO;
import com.tj.crypto.trading.paper.persistence.PaperPositionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Command side of the persistent paper OMS. */
@Service
@RequiredArgsConstructor
public class PaperOrderService {
    private final PaperAccountLifecycleService accountLifecycleService;
    private final InstrumentMetadataService metadataService;
    private final PaperMarketDataService marketDataService;
    private final PaperPositionMapper positionMapper;
    private final PaperReservationService reservationService;
    private final PaperSettlementService settlementService;
    private final OmsOrderMapper orderMapper;
    private final OmsPersistenceService omsPersistenceService;
    private final ExecutionCostModel executionCostModel;
    private final ExecutionSimulationProperties simulationProperties;
    private final KillSwitch killSwitch;
    private final PaperPreTradeRiskService preTradeRiskService;
    private final OutboxService outboxService;

    @Transactional
    public Order place(PaperOrderRequest request) {
        long now = System.currentTimeMillis();
        PaperAccountDO account = lockAccount(request.accountId());
        String clientOrderId = request.clientOrderId() == null || request.clientOrderId().isBlank()
                ? UUID.randomUUID().toString() : request.clientOrderId().trim();
        InstrumentMetadata metadata = metadataService.require(
                request.exchange(), request.marketType(), request.symbol(), now);
        OmsOrderDO duplicate = orderMapper.selectByClientOrderId(clientOrderId);
        if (duplicate != null) return verifyIdempotentReplay(account, request, metadata, duplicate);
        PaperMarkPriceDO mark = marketDataService.require(
                request.exchange(), request.marketType(), request.symbol());
        ensureFreshMark(mark, now);
        BigDecimal quantity = metadata.alignQuantity(request.quantity());
        BigDecimal orderPrice = request.orderType() == com.tj.crypto.execution.model.OrderType.LIMIT
                ? metadata.alignPrice(request.limitPrice()) : metadata.alignPrice(mark.getPrice());
        metadata.validate(orderPrice, quantity, request.leverage());

        PaperPositionDO position = positionMapper.selectForUpdate(account.getAccountId(),
                metadata.exchange().name(), metadata.marketType().name(), metadata.symbol());
        PaperOrderIntent intent = resolveIntent(account, request, clientOrderId, metadata,
                position, quantity, orderPrice, mark, now);
        Order order = createOrder(intent);
        OmsFillMetadata orderMetadata = orderMetadata(intent);
        omsPersistenceService.recordVenue(order, OrderEvent.created(order.orderId(), now),
                null, orderMetadata, "PAPER");

        OrderEvent submittedEvent = OrderEvent.submitted(order.orderId(), now);
        order = OrderStateMachine.transition(order, submittedEvent);
        omsPersistenceService.recordVenue(order, submittedEvent, null, orderMetadata, "PAPER");

        OrderRejectReason riskRejection = riskRejection(account, intent);
        if (riskRejection != null) return reject(order, riskRejection, orderMetadata, now);

        try {
            reservationService.reserve(intent, order.orderId());
        } catch (IllegalArgumentException e) {
            return reject(order, OrderRejectReason.INSUFFICIENT_BALANCE, orderMetadata, now);
        }

        OrderEvent acknowledgedEvent = OrderEvent.acknowledged(order.orderId(), now);
        order = OrderStateMachine.transition(order, acknowledgedEvent);
        omsPersistenceService.recordVenue(order, acknowledgedEvent, null, orderMetadata, "PAPER");
        outboxService.append("OMS_ORDER", order.orderId(), "PAPER_ORDER_ACCEPTED",
                orderPayload(account.getAccountId(), order), intent.correlationId(), now);

        if (order.type() == com.tj.crypto.execution.model.OrderType.MARKET) {
            ExecutionFillPlan plan = normalizedPlan(metadata, order,
                    plan(order, mark, now));
            if (plan.hasFill()) {
                order = settlementService.settle(order, metadata, intent.leverage(), plan,
                        intent.correlationId(), now);
            }
        }
        return order;
    }

    @Transactional
    public Order cancel(String orderId, String accountId, String correlationId) {
        OmsOrderDO stored = orderMapper.selectByIdForUpdate(orderId);
        if (stored == null) throw new IllegalArgumentException("Unknown order: " + orderId);
        PaperAccountDO account = lockAccount(accountId == null ? stored.getAccountId() : accountId);
        if (!account.getAccountId().equals(stored.getAccountId())) {
            throw new IllegalArgumentException("Order belongs to a different paper account");
        }
        Order order = OmsOrderDomainConverter.toDomain(stored);
        if (order.status() != OrderStatus.ACKNOWLEDGED
                && order.status() != OrderStatus.PARTIALLY_FILLED) {
            throw new IllegalStateException("Order cannot be cancelled from status " + order.status());
        }
        OmsFillMetadata metadata = orderMetadata(stored, correlationId);
        long now = System.currentTimeMillis();
        OrderEvent requestedEvent = OrderEvent.cancelRequested(orderId, now);
        order = OrderStateMachine.transition(order, requestedEvent);
        omsPersistenceService.recordVenue(order, requestedEvent, null, metadata, "PAPER");
        reservationService.release(orderId, now);
        OrderEvent cancelledEvent = OrderEvent.cancelled(orderId, now);
        order = OrderStateMachine.transition(order, cancelledEvent);
        omsPersistenceService.recordVenue(order, cancelledEvent, null, metadata, "PAPER");
        outboxService.append("OMS_ORDER", orderId, "PAPER_ORDER_CANCELLED",
                orderPayload(account.getAccountId(), order), correlationId, now);
        return order;
    }

    @Transactional
    public Order matchOne(String orderId, PaperMarkPriceDO mark) {
        OmsOrderDO stored = orderMapper.selectByIdForUpdate(orderId);
        if (stored == null) return null;
        Order order = OmsOrderDomainConverter.toDomain(stored);
        if (order.status() != OrderStatus.ACKNOWLEDGED
                && order.status() != OrderStatus.PARTIALLY_FILLED) return order;
        lockAccount(stored.getAccountId());
        InstrumentMetadata metadata = metadataService.require(order.instrument(), mark.getEventTimeMs());
        ExecutionFillPlan plan = normalizedPlan(metadata, order,
                plan(order, mark, mark.getEventTimeMs()));
        if (!plan.hasFill()) return order;
        return settlementService.settle(order, metadata, stored.getLeverage(), plan,
                stored.getCorrelationId(), mark.getEventTimeMs());
    }

    private PaperAccountDO lockAccount(String accountId) {
        PaperAccountDO running = accountLifecycleService.running();
        String resolved = accountId;
        if ((resolved == null || resolved.isBlank()) && running != null) resolved = running.getAccountId();
        return accountLifecycleService.lockRunning(resolved);
    }

    private PaperOrderIntent resolveIntent(PaperAccountDO account, PaperOrderRequest request,
                                           String clientOrderId, InstrumentMetadata metadata,
                                           PaperPositionDO position, BigDecimal quantity,
                                           BigDecimal orderPrice, PaperMarkPriceDO mark, long now) {
        OrderSide requestedSide = request.side() == TradeSide.BUY ? OrderSide.LONG : OrderSide.SHORT;
        PaperOrderAction action;
        OrderSide positionSide;
        boolean reduceOnly;
        int leverage = request.leverage();

        if (metadata.marketType() == MarketType.SPOT) {
            if (request.side() == TradeSide.BUY) {
                if (request.reduceOnly()) throw new IllegalArgumentException("Spot BUY cannot be reduce-only");
                action = PaperOrderAction.OPEN;
                positionSide = OrderSide.LONG;
                reduceOnly = false;
            } else {
                if (position == null || !"LONG".equals(position.getSide())) {
                    throw new IllegalArgumentException("Spot SELL requires an existing long position");
                }
                action = PaperOrderAction.CLOSE;
                positionSide = OrderSide.LONG;
                reduceOnly = true;
                validateCloseQuantity(position, quantity);
            }
            leverage = 1;
        } else if (position == null) {
            if (request.reduceOnly()) throw new IllegalArgumentException("Reduce-only order has no position");
            action = PaperOrderAction.OPEN;
            positionSide = requestedSide;
            reduceOnly = false;
        } else if (position.getSide().equals(requestedSide.name())) {
            if (request.reduceOnly()) throw new IllegalArgumentException("Reduce-only side does not reduce the position");
            if (!position.getStrategyId().equals(request.strategyId())) {
                throw new IllegalArgumentException("Existing position is owned by another strategy");
            }
            action = PaperOrderAction.OPEN;
            positionSide = requestedSide;
            reduceOnly = false;
            leverage = position.getLeverage();
        } else {
            action = PaperOrderAction.CLOSE;
            positionSide = OrderSide.valueOf(position.getSide());
            reduceOnly = true;
            leverage = position.getLeverage();
            validateCloseQuantity(position, quantity);
        }
        return new PaperOrderIntent(account.getAccountId(), clientOrderId, request.strategyId(),
                metadata, request.side(), requestedSide, positionSide, action, request.orderType(),
                quantity, mark.getPrice(), orderPrice, leverage, reduceOnly,
                request.correlationId(), now);
    }

    private Order createOrder(PaperOrderIntent intent) {
        return new Order(UUID.randomUUID().toString(), intent.clientOrderId(),
                intent.metadata().instrument(), intent.requestedSide(), intent.orderType(),
                intent.quantity(), intent.orderPrice(), BigDecimal.ZERO, null,
                OrderStatus.CREATED, OrderRejectReason.NONE, intent.timestamp(), 0, 0, 0,
                intent.strategyId(), intent.tradeSide(), intent.positionSide(), intent.reduceOnly());
    }

    private OrderRejectReason riskRejection(PaperAccountDO account, PaperOrderIntent intent) {
        if (killSwitch.getMode() == KillSwitch.Mode.HALT) return OrderRejectReason.KILL_SWITCH;
        if (killSwitch.getMode() == KillSwitch.Mode.CLOSE_ONLY
                && intent.action() == PaperOrderAction.OPEN) return OrderRejectReason.CLOSE_ONLY;
        PaperRiskDecision decision = preTradeRiskService.check(account, intent);
        return decision.passed() ? null : decision.reason();
    }

    private Order reject(Order order, OrderRejectReason reason,
                         OmsFillMetadata metadata, long now) {
        OrderEvent event = OrderEvent.rejected(order.orderId(), now, reason);
        Order rejected = OrderStateMachine.transition(order, event);
        omsPersistenceService.recordVenue(rejected, event, null, metadata, "PAPER");
        outboxService.append("OMS_ORDER", order.orderId(), "PAPER_ORDER_REJECTED",
                Map.of("orderId", order.orderId(), "reason", reason.name()),
                metadata.correlationId(), now);
        return rejected;
    }

    private ExecutionFillPlan plan(Order order, PaperMarkPriceDO mark, long eventTime) {
        BigDecimal remaining = order.quantity().subtract(order.filledQuantity());
        return executionCostModel.plan(new ExecutionCostRequest(order.tradeSide(), order.type(),
                remaining, mark.getPrice(), order.price(), mark.getHighPrice(), mark.getLowPrice(),
                mark.getBaseVolume(), Math.max(0, eventTime - mark.getEventTimeMs())));
    }

    private ExecutionFillPlan normalizedPlan(InstrumentMetadata metadata, Order order,
                                             ExecutionFillPlan plan) {
        if (!plan.hasFill()) return plan;
        BigDecimal remaining = order.quantity().subtract(order.filledQuantity());
        BigDecimal quantity = metadata.alignQuantity(plan.filledQuantity().min(remaining));
        if (quantity.signum() <= 0) {
            return new ExecutionFillPlan(false, BigDecimal.ZERO, remaining, null,
                    plan.spreadBps(), plan.impactBps(), plan.latencyBps(),
                    plan.totalSlippageBps(), plan.capacityNotional(), plan.liquidityRole());
        }
        return new ExecutionFillPlan(true, quantity, remaining.subtract(quantity), plan.fillPrice(),
                plan.spreadBps(), plan.impactBps(), plan.latencyBps(),
                plan.totalSlippageBps(), plan.capacityNotional(), plan.liquidityRole());
    }

    private void ensureFreshMark(PaperMarkPriceDO mark, long now) {
        long age = Math.max(0, now - mark.getEventTimeMs());
        if (age > simulationProperties.getMaxMarkAgeMs()) {
            throw new IllegalStateException("Paper market price is stale: ageMs=" + age);
        }
    }

    private void validateCloseQuantity(PaperPositionDO position, BigDecimal quantity) {
        if (quantity.compareTo(position.getQuantity()) > 0) {
            throw new IllegalArgumentException("Close quantity exceeds position quantity");
        }
    }

    private Order verifyIdempotentReplay(PaperAccountDO account, PaperOrderRequest request,
                                         InstrumentMetadata metadata, OmsOrderDO existing) {
        BigDecimal normalizedQuantity = metadata.alignQuantity(request.quantity());
        BigDecimal normalizedLimitPrice = request.orderType() == com.tj.crypto.execution.model.OrderType.LIMIT
                ? metadata.alignPrice(request.limitPrice()) : null;
        boolean same = account.getAccountId().equals(existing.getAccountId())
                && request.exchange().name().equals(existing.getExchange())
                && request.marketType().name().equals(existing.getMarketType())
                && request.symbol().replace("-", "").equalsIgnoreCase(existing.getSymbol())
                && request.side().name().equals(existing.getTradeSide())
                && request.orderType().name().equals(existing.getOrderType())
                && normalizedQuantity.compareTo(existing.getQuantity()) == 0
                && (normalizedLimitPrice == null
                    || normalizedLimitPrice.compareTo(existing.getPrice()) == 0)
                && (!request.reduceOnly() || Boolean.TRUE.equals(existing.getReduceOnly()));
        if (!same) throw new IllegalArgumentException("clientOrderId was already used for a different order");
        return OmsOrderDomainConverter.toDomain(existing);
    }

    private OmsFillMetadata orderMetadata(PaperOrderIntent intent) {
        return new OmsFillMetadata(intent.accountId(), intent.correlationId(), null,
                BigDecimal.ZERO, intent.metadata().settleAsset(), null,
                intent.referencePrice(), intent.referencePrice(), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, intent.leverage(), "ISOLATED");
    }

    private OmsFillMetadata orderMetadata(OmsOrderDO order, String correlationId) {
        return new OmsFillMetadata(order.getAccountId(),
                correlationId == null ? order.getCorrelationId() : correlationId, null,
                BigDecimal.ZERO, null, null, order.getPrice(), order.getPrice(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                order.getLeverage() == null ? 1 : order.getLeverage(), order.getMarginMode());
    }

    private Map<String, Object> orderPayload(String accountId, Order order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountId", accountId);
        payload.put("orderId", order.orderId());
        payload.put("clientOrderId", order.clientOrderId());
        payload.put("status", order.status().name());
        payload.put("quantity", order.quantity());
        payload.put("filledQuantity", order.filledQuantity());
        return payload;
    }
}
