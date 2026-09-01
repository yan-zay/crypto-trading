package com.tj.crypto.trading.pipeline;

import com.tj.crypto.common.domain.TradeSide;

import java.math.BigDecimal;
import java.time.Instant;

/** Shared deterministic Target -> Risk -> Plan compiler; it never grants execution authority. */
public final class DeterministicTargetPipeline {

    public OrderIntent plan(RiskAdjustedTarget adjusted, PositionSnapshot current,
                            BigDecimal referencePrice, Instant now) {
        if (adjusted == null || current == null || referencePrice == null || now == null) {
            throw new IllegalArgumentException("target, position, price and time are required");
        }
        PortfolioTarget target = adjusted.target();
        if (!target.instrumentId().equals(current.instrumentId())
                || !target.quantityUnit().equals(current.quantityUnit())) {
            throw new IllegalArgumentException("target and position scope do not match");
        }
        if (now.isBefore(target.createdAt()) || !now.isBefore(target.expiresAt())
                || now.isBefore(current.observedAt()) || !now.isBefore(current.expiresAt())) {
            throw new IllegalStateException("target or position facts are not current");
        }
        BigDecimal existing = current.signedQuantity();
        BigDecimal desired = target.targetQuantity();
        if (existing.signum() != 0 && desired.signum() != 0
                && existing.signum() != desired.signum()) {
            throw new IllegalStateException("position reversal requires a separate flat target");
        }
        BigDecimal delta = desired.subtract(existing).stripTrailingZeros();
        if (delta.signum() == 0) return null;
        BigDecimal quantity = delta.abs();
        if (quantity.compareTo(adjusted.maxOrderQuantity()) > 0) {
            throw new IllegalStateException("target delta exceeds risk-adjusted order limit");
        }
        boolean reduceOnly = existing.signum() != 0
                && desired.abs().compareTo(existing.abs()) < 0;
        return new OrderIntent(target.strategyId(), target.instrumentId(),
                delta.signum() > 0 ? TradeSide.BUY : TradeSide.SELL, quantity, reduceOnly,
                referencePrice, target.policyVersion(), now, target.expiresAt());
    }
}
