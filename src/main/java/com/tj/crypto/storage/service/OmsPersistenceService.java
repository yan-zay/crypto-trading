package com.tj.crypto.storage.service;

import com.tj.crypto.backtest.portfolio.Trade;
import com.tj.crypto.execution.journal.ExecutionJournal;
import com.tj.crypto.execution.journal.OmsFillMetadata;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderEvent;
import com.tj.crypto.storage.converter.TradeConverter;
import com.tj.crypto.storage.entity.OmsFillDO;
import com.tj.crypto.storage.entity.OmsOrderDO;
import com.tj.crypto.storage.entity.OmsOrderEventDO;
import com.tj.crypto.storage.entity.TradeRecordDO;
import com.tj.crypto.storage.mapper.OmsFillMapper;
import com.tj.crypto.storage.mapper.OmsOrderEventMapper;
import com.tj.crypto.storage.mapper.OmsOrderMapper;
import com.tj.crypto.storage.mapper.TradeRecordMapper;
import com.tj.crypto.trading.venue.riskreservation.LiveRiskReservationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * OMS 事务持久化实现。
 *
 * <p>每个状态变化在一个事务中更新订单快照并追加事件；成交事件同时追加 fill，
 * 平仓产生的 Trade 也在同一事务中落库。订单快照使用 order_id 幂等 upsert。
 */
@Service
@RequiredArgsConstructor
public class OmsPersistenceService implements ExecutionJournal {

    private final OmsOrderMapper orderMapper;
    private final OmsOrderEventMapper eventMapper;
    private final OmsFillMapper fillMapper;
    private final TradeRecordMapper tradeRecordMapper;
    private final LiveRiskReservationMapper riskReservationMapper;

    @Override
    @Transactional
    public void record(Order order, OrderEvent event, Trade trade) {
        record(order, event, trade, OmsFillMetadata.empty(), "LEGACY");
    }

    /** Records a paper/live venue event with account, fee and execution-quality facts. */
    @Transactional
    public void recordVenue(Order order, OrderEvent event, Trade trade,
                            OmsFillMetadata metadata, String orderSource) {
        record(order, event, trade, metadata == null ? OmsFillMetadata.empty() : metadata,
                orderSource == null ? "VENUE" : orderSource);
    }

    /**
     * Claims a brand-new live command with a plain INSERT. A duplicate clientOrderId must fail
     * atomically; using the snapshot upsert here would let two internal orders share one external
     * idempotency key and could dispatch both commands.
     */
    @Transactional
    public void recordInitialLiveCommand(Order order, OrderEvent event,
                                         OmsFillMetadata metadata) {
        OmsFillMetadata safeMetadata = metadata == null ? OmsFillMetadata.empty() : metadata;
        orderMapper.insert(toOrderDO(order, event, safeMetadata, "LIVE"));
        eventMapper.insert(toEventDO(order, event));
    }

    /**
     * Idempotently records a venue user-stream event. The external event is inserted first,
     * so a reconnect replay cannot append a duplicate fill.
     */
    @Transactional
    public boolean recordExternal(Order order, OrderEvent event, Trade trade,
                                  OmsFillMetadata metadata, String orderSource,
                                  String externalEventId, String payloadChecksum,
                                  String venueOrderId, String externalStatus) {
        if (externalEventId == null || externalEventId.isBlank()) {
            throw new IllegalArgumentException("externalEventId is required");
        }
        OmsOrderEventDO eventDO = toEventDO(order, event);
        eventDO.setExternalEventId(externalEventId);
        eventDO.setPayloadChecksum(payloadChecksum);
        if (eventMapper.insertExternalIgnore(eventDO) == 0) return false;

        OmsFillMetadata safeMetadata = metadata == null ? OmsFillMetadata.empty() : metadata;
        String safeOrderSource = orderSource == null ? "LIVE" : orderSource;
        OmsOrderDO orderDO = toOrderDO(order, event, safeMetadata, safeOrderSource);
        orderDO.setVenueOrderId(venueOrderId);
        orderDO.setExternalStatus(externalStatus);
        orderMapper.upsert(orderDO);
        syncRiskReservation(order, event, safeOrderSource);
        if (event.fillPrice() != null && event.fillQuantity() != null
                && event.fillQuantity().signum() > 0) {
            fillMapper.insert(toFillDO(order, eventDO.getEventId(), event, safeMetadata));
        }
        if (trade != null) {
            TradeRecordDO tradeDO = TradeConverter.toDO(trade);
            tradeDO.setStrategyId(order.strategyId());
            tradeDO.setOrderId(order.orderId());
            tradeRecordMapper.insert(tradeDO);
        }
        return true;
    }

    @Transactional
    public void attachVenue(String orderId, String venueOrderId, String externalStatus) {
        if (orderMapper.attachVenue(orderId, venueOrderId, externalStatus) != 1) {
            throw new IllegalArgumentException("Unknown OMS order: " + orderId);
        }
    }

    /** Persists an indeterminate outcome without pretending a venue transition occurred. */
    @Transactional
    public void markUnknown(String orderId, String externalStatus, long eventTime) {
        if (orderMapper.markUnknown(orderId, externalStatus, eventTime) != 1) return;
        riskReservationMapper.markUnknown(orderId);
        OmsOrderEventDO event = new OmsOrderEventDO();
        event.setEventId(UUID.randomUUID().toString());
        event.setOrderId(orderId);
        event.setEventType("UNKNOWN");
        event.setOrderStatus("UNKNOWN");
        event.setEventTime(eventTime);
        eventMapper.insert(event);
    }

    private void record(Order order, OrderEvent event, Trade trade,
                        OmsFillMetadata metadata, String orderSource) {
        orderMapper.upsert(toOrderDO(order, event, metadata, orderSource));
        syncRiskReservation(order, event, orderSource);

        OmsOrderEventDO eventDO = toEventDO(order, event);
        eventMapper.insert(eventDO);

        if (event.fillPrice() != null && event.fillQuantity() != null
                && event.fillQuantity().signum() > 0) {
            fillMapper.insert(toFillDO(order, eventDO.getEventId(), event, metadata));
        }

        if (trade != null) {
            TradeRecordDO tradeDO = TradeConverter.toDO(trade);
            tradeDO.setStrategyId(order.strategyId());
            tradeDO.setOrderId(order.orderId());
            tradeRecordMapper.insert(tradeDO);
        }
    }

    private void syncRiskReservation(Order order, OrderEvent event, String orderSource) {
        if (!"LIVE".equalsIgnoreCase(orderSource)) return;
        riskReservationMapper.syncFromOrder(order.orderId(), order.status().name(),
                order.filledQuantity(), event.timestamp());
    }

    @Override
    public boolean requiredForExecution() {
        return true;
    }

    private OmsOrderDO toOrderDO(Order order, OrderEvent event,
                                 OmsFillMetadata metadata, String orderSource) {
        OmsOrderDO target = new OmsOrderDO();
        target.setOrderId(order.orderId());
        target.setClientOrderId(order.clientOrderId());
        target.setAccountId(metadata.accountId());
        target.setOrderSource(orderSource);
        target.setCorrelationId(metadata.correlationId());
        target.setLeverage(metadata.leverage() < 1 ? 1 : metadata.leverage());
        target.setMarginMode(metadata.marginMode() == null ? "ISOLATED" : metadata.marginMode());
        target.setStateVersion(0L);
        target.setLastEventAtMs(event.timestamp());
        target.setStrategyId(order.strategyId());
        target.setExchange(order.instrument().exchange().name());
        target.setMarketType(order.instrument().marketType().name());
        target.setSymbol(order.instrument().symbol());
        target.setTradeSide(order.tradeSide().name());
        target.setRequestedSide(order.side().name());
        target.setPositionSide(order.positionSide().name());
        target.setReduceOnly(order.reduceOnly());
        target.setOrderType(order.type().name());
        target.setQuantity(order.quantity());
        target.setPrice(order.price());
        target.setFilledQuantity(order.filledQuantity());
        target.setAvgFillPrice(order.avgFillPrice());
        target.setStatus(order.status().name());
        target.setRejectReason(order.rejectReason().name());
        target.setCreatedAtMs(order.createdAt());
        target.setSubmittedAtMs(zeroToNull(order.submittedAt()));
        target.setFilledAtMs(zeroToNull(order.filledAt()));
        target.setCancelledAtMs(zeroToNull(order.cancelledAt()));
        return target;
    }

    private OmsOrderEventDO toEventDO(Order order, OrderEvent event) {
        OmsOrderEventDO target = new OmsOrderEventDO();
        target.setEventId(UUID.randomUUID().toString());
        target.setOrderId(order.orderId());
        target.setEventType(event.eventType().name());
        target.setOrderStatus(order.status().name());
        target.setEventTime(event.timestamp());
        target.setFillPrice(event.fillPrice());
        target.setFillQuantity(event.fillQuantity());
        target.setRejectReason(event.rejectReason() == null ? null : event.rejectReason().name());
        return target;
    }

    private OmsFillDO toFillDO(Order order, String eventId, OrderEvent event,
                               OmsFillMetadata metadata) {
        OmsFillDO target = new OmsFillDO();
        target.setFillId(UUID.randomUUID().toString());
        target.setAccountId(metadata.accountId());
        target.setStrategyId(order.strategyId());
        target.setOrderId(order.orderId());
        target.setEventId(eventId);
        target.setExchangeTradeId(metadata.exchangeTradeId());
        target.setFillPrice(event.fillPrice());
        target.setFillQuantity(event.fillQuantity());
        target.setReferencePrice(metadata.referencePrice());
        target.setArrivalPrice(metadata.arrivalPrice());
        target.setSpreadBps(defaultZero(metadata.spreadBps()));
        target.setImpactBps(defaultZero(metadata.impactBps()));
        target.setSlippageBps(defaultZero(metadata.slippageBps()));
        target.setFee(defaultZero(metadata.fee()));
        target.setFeeCurrency(metadata.feeCurrency());
        target.setLiquidityRole(metadata.liquidityRole() == null ? "SIMULATED" : metadata.liquidityRole());
        target.setFillTime(event.timestamp());
        return target;
    }

    private BigDecimal defaultZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private Long zeroToNull(long value) {
        return value == 0 ? null : value;
    }
}
