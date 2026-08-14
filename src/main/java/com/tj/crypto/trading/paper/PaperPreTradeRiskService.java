package com.tj.crypto.trading.paper;

import com.tj.crypto.execution.model.OrderRejectReason;
import com.tj.crypto.risk.RiskProperties;
import com.tj.crypto.storage.entity.OmsOrderDO;
import com.tj.crypto.storage.mapper.OmsOrderMapper;
import com.tj.crypto.trading.paper.persistence.PaperAccountDO;
import com.tj.crypto.trading.paper.persistence.PaperEquitySnapshotDO;
import com.tj.crypto.trading.paper.persistence.PaperEquitySnapshotMapper;
import com.tj.crypto.trading.paper.persistence.PaperPositionDO;
import com.tj.crypto.trading.paper.persistence.PaperPositionMapper;
import com.tj.crypto.trading.paper.persistence.PaperTradeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

/** Database-backed pre-trade limits whose state survives process restarts. */
@Service
@RequiredArgsConstructor
class PaperPreTradeRiskService {
    private static final MathContext MC = new MathContext(18, RoundingMode.HALF_UP);
    private final RiskProperties properties;
    private final PaperPositionMapper positionMapper;
    private final PaperTradeMapper tradeMapper;
    private final PaperEquitySnapshotMapper equityMapper;
    private final OmsOrderMapper orderMapper;

    PaperRiskDecision check(PaperAccountDO account, PaperOrderIntent intent) {
        if (intent.reduceOnly()) return PaperRiskDecision.pass();
        BigDecimal equity = equity(account);
        if (equity.signum() <= 0) {
            return PaperRiskDecision.reject(OrderRejectReason.RISK_REJECTED,
                    "Paper account equity is not positive");
        }
        BigDecimal orderNotional = intent.quantity().multiply(intent.orderPrice(), MC)
                .multiply(intent.metadata().contractMultiplier(), MC);
        if (exceeds(orderNotional, equity, properties.getMaxSizePct())) {
            return PaperRiskDecision.reject(OrderRejectReason.EXPOSURE_LIMIT,
                    "Order notional exceeds max-size limit");
        }

        List<PaperPositionDO> positions = positionMapper.selectByAccount(account.getAccountId());
        List<OmsOrderDO> activeOrders = orderMapper.selectActiveByAccount(account.getAccountId()).stream()
                .filter(order -> !intent.clientOrderId().equals(order.getClientOrderId()))
                .toList();
        BigDecimal gross = positions.stream().map(this::positionNotional)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(activeOrders.stream().filter(order -> !Boolean.TRUE.equals(order.getReduceOnly()))
                        .map(this::orderNotional).reduce(BigDecimal.ZERO, BigDecimal::add));
        if (exceeds(gross.add(orderNotional), equity, properties.getMaxTotalExposurePct())) {
            return PaperRiskDecision.reject(OrderRejectReason.EXPOSURE_LIMIT,
                    "Portfolio gross exposure exceeds limit");
        }

        BigDecimal symbolExposure = positions.stream()
                .filter(position -> sameInstrument(position, intent))
                .map(this::positionNotional).reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(activeOrders.stream().filter(order -> !Boolean.TRUE.equals(order.getReduceOnly()))
                        .filter(order -> sameInstrument(order, intent))
                        .map(this::orderNotional).reduce(BigDecimal.ZERO, BigDecimal::add));
        if (exceeds(symbolExposure.add(orderNotional), equity,
                properties.getMaxSymbolExposurePct())) {
            return PaperRiskDecision.reject(OrderRejectReason.EXPOSURE_LIMIT,
                    "Per-symbol exposure exceeds limit");
        }

        BigDecimal strategyExposure = positions.stream()
                .filter(position -> intent.strategyId().equals(position.getStrategyId()))
                .map(this::positionNotional).reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(activeOrders.stream().filter(order -> !Boolean.TRUE.equals(order.getReduceOnly()))
                        .filter(order -> intent.strategyId().equals(order.getStrategyId()))
                        .map(this::orderNotional).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal strategyLimitBase = account.getInitialBalance();
        if (exceeds(strategyExposure.add(orderNotional), strategyLimitBase,
                properties.getMaxStrategyBudgetPct())) {
            return PaperRiskDecision.reject(OrderRejectReason.EXPOSURE_LIMIT,
                    "Strategy budget exceeds limit");
        }

        long dayStart = (intent.timestamp() / 86_400_000L) * 86_400_000L;
        BigDecimal dailyLoss = tradeMapper.sumLoss(account.getAccountId(), dayStart, intent.timestamp());
        BigDecimal maxDailyLoss = account.getInitialBalance()
                .multiply(properties.getMaxDailyLossPct(), MC)
                .divide(BigDecimal.valueOf(100), MC);
        if (dailyLoss.compareTo(maxDailyLoss) >= 0) {
            return PaperRiskDecision.reject(OrderRejectReason.RISK_REJECTED,
                    "Daily realized loss limit reached");
        }
        return PaperRiskDecision.pass();
    }

    private BigDecimal equity(PaperAccountDO account) {
        PaperEquitySnapshotDO snapshot = equityMapper.selectLatest(account.getAccountId());
        return snapshot == null ? account.getInitialBalance() : snapshot.getEquity();
    }

    private boolean exceeds(BigDecimal value, BigDecimal base, BigDecimal percentage) {
        BigDecimal limit = base.multiply(percentage, MC).divide(BigDecimal.valueOf(100), MC);
        return value.compareTo(limit) > 0;
    }

    private BigDecimal positionNotional(PaperPositionDO position) {
        return position.getQuantity().multiply(position.getMarkPrice(), MC)
                .multiply(position.getContractMultiplier(), MC);
    }

    private BigDecimal orderNotional(OmsOrderDO order) {
        return order.getPrice() == null ? BigDecimal.ZERO
                : order.getQuantity().subtract(order.getFilledQuantity()).max(BigDecimal.ZERO)
                .multiply(order.getPrice(), MC);
    }

    private boolean sameInstrument(PaperPositionDO position, PaperOrderIntent intent) {
        return position.getExchange().equals(intent.metadata().exchange().name())
                && position.getMarketType().equals(intent.metadata().marketType().name())
                && position.getSymbol().equals(intent.metadata().symbol());
    }

    private boolean sameInstrument(OmsOrderDO order, PaperOrderIntent intent) {
        return order.getExchange().equalsIgnoreCase(intent.metadata().exchange().name())
                && order.getMarketType().equals(intent.metadata().marketType().name())
                && order.getSymbol().equals(intent.metadata().symbol());
    }
}
