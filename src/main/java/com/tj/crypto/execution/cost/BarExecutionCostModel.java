package com.tj.crypto.execution.cost;

import com.tj.crypto.common.domain.TradeSide;
import com.tj.crypto.execution.model.OrderType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Parameterized bar-liquidity model used when L2 order-book data is unavailable.
 * Every cost component is returned to the report instead of being hidden in one price.
 */
@Component
@RequiredArgsConstructor
public class BarExecutionCostModel implements ExecutionCostModel {
    private static final BigDecimal TEN_THOUSAND = new BigDecimal("10000");
    private static final MathContext MC = new MathContext(18, RoundingMode.HALF_UP);
    private final ExecutionSimulationProperties properties;

    @Override
    public ExecutionFillPlan plan(ExecutionCostRequest request) {
        if (!isMarketable(request)) {
            return noFill(request);
        }

        BigDecimal capacityQuantity = request.baseVolume().signum() > 0
                ? request.baseVolume().multiply(properties.getMaxParticipationRate(), MC)
                : request.requestedQuantity();
        BigDecimal fillQuantity = request.requestedQuantity().min(capacityQuantity);
        if (fillQuantity.signum() <= 0) return noFill(request);

        boolean maker = request.orderType() == OrderType.LIMIT;
        BigDecimal spreadBps = maker ? BigDecimal.ZERO
                : properties.getSpreadBps().divide(BigDecimal.valueOf(2), MC);
        BigDecimal participation = request.baseVolume().signum() > 0
                ? fillQuantity.divide(request.baseVolume(), MC) : BigDecimal.ZERO;
        BigDecimal impactBps = maker ? BigDecimal.ZERO : BigDecimal.valueOf(
                        properties.getImpactCoefficientBps().doubleValue()
                                * Math.sqrt(Math.max(0D, participation.doubleValue())))
                .min(properties.getMaxImpactBps());
        BigDecimal latencyBps = maker ? BigDecimal.ZERO
                : properties.getLatencyBpsPerSecond()
                .multiply(BigDecimal.valueOf(request.latencyMs()), MC)
                .divide(BigDecimal.valueOf(1000), MC);
        BigDecimal totalBps = spreadBps.add(impactBps, MC).add(latencyBps, MC);
        BigDecimal fillPrice = maker
                ? request.limitPrice()
                : applyDirectionalCost(request.referencePrice(), request.side(), totalBps);

        return new ExecutionFillPlan(
                true, fillQuantity, request.requestedQuantity().subtract(fillQuantity),
                fillPrice, spreadBps, impactBps, latencyBps, totalBps,
                capacityQuantity.multiply(request.referencePrice(), MC), maker ? "MAKER" : "TAKER");
    }

    private boolean isMarketable(ExecutionCostRequest request) {
        if (request.orderType() == OrderType.MARKET) return true;
        return request.side() == TradeSide.BUY
                ? request.lowPrice().compareTo(request.limitPrice()) <= 0
                : request.highPrice().compareTo(request.limitPrice()) >= 0;
    }

    private ExecutionFillPlan noFill(ExecutionCostRequest request) {
        return new ExecutionFillPlan(false, BigDecimal.ZERO, request.requestedQuantity(),
                null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, "NONE");
    }

    private BigDecimal applyDirectionalCost(BigDecimal price, TradeSide side, BigDecimal bps) {
        BigDecimal ratio = bps.divide(TEN_THOUSAND, MC);
        BigDecimal multiplier = side == TradeSide.BUY
                ? BigDecimal.ONE.add(ratio) : BigDecimal.ONE.subtract(ratio);
        return price.multiply(multiplier, MC);
    }
}
