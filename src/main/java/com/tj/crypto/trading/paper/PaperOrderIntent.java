package com.tj.crypto.trading.paper;

import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.common.domain.TradeSide;
import com.tj.crypto.execution.model.OrderType;
import com.tj.crypto.trading.instrument.InstrumentMetadata;

import java.math.BigDecimal;

record PaperOrderIntent(
        String accountId,
        String clientOrderId,
        String strategyId,
        InstrumentMetadata metadata,
        TradeSide tradeSide,
        OrderSide requestedSide,
        OrderSide positionSide,
        PaperOrderAction action,
        OrderType orderType,
        BigDecimal quantity,
        BigDecimal referencePrice,
        BigDecimal orderPrice,
        int leverage,
        boolean reduceOnly,
        String correlationId,
        long timestamp
) {}
