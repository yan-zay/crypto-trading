package com.tj.crypto.trading.paper;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.common.domain.TradeSide;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderRejectReason;
import com.tj.crypto.execution.model.OrderStatus;
import com.tj.crypto.execution.model.OrderType;
import com.tj.crypto.storage.entity.OmsOrderDO;

import java.math.BigDecimal;

/** Rehydrates immutable order state from the OMS snapshot during matching or recovery. */
public final class OmsOrderDomainConverter {
    private OmsOrderDomainConverter() {}

    public static Order toDomain(OmsOrderDO source) {
        Instrument instrument = Instrument.of(exchange(source.getExchange()),
                MarketType.valueOf(source.getMarketType()), source.getSymbol());
        return new Order(source.getOrderId(), source.getClientOrderId(), instrument,
                OrderSide.valueOf(source.getRequestedSide()), OrderType.valueOf(source.getOrderType()),
                source.getQuantity(), source.getPrice(), zero(source.getFilledQuantity()),
                source.getAvgFillPrice(), OrderStatus.valueOf(source.getStatus()),
                source.getRejectReason() == null ? OrderRejectReason.NONE
                        : OrderRejectReason.valueOf(source.getRejectReason()),
                source.getCreatedAtMs(), zero(source.getSubmittedAtMs()), zero(source.getFilledAtMs()),
                zero(source.getCancelledAtMs()), source.getStrategyId(),
                TradeSide.valueOf(source.getTradeSide()), OrderSide.valueOf(source.getPositionSide()),
                Boolean.TRUE.equals(source.getReduceOnly()));
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static long zero(Long value) {
        return value == null ? 0L : value;
    }

    private static Exchange exchange(String value) {
        for (Exchange exchange : Exchange.values()) {
            if (exchange.name().equalsIgnoreCase(value) || exchange.getCode().equalsIgnoreCase(value)) {
                return exchange;
            }
        }
        throw new IllegalArgumentException("Unknown OMS exchange: " + value);
    }
}
