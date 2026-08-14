package com.tj.crypto.trading.paper;

import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.execution.OrderStateMachine;
import com.tj.crypto.execution.cost.ExecutionFillPlan;
import com.tj.crypto.execution.journal.OmsFillMetadata;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderEvent;
import com.tj.crypto.reliability.outbox.OutboxService;
import com.tj.crypto.storage.service.OmsPersistenceService;
import com.tj.crypto.trading.instrument.InstrumentMetadata;
import com.tj.crypto.trading.paper.ledger.DoubleEntryLedgerService;
import com.tj.crypto.trading.paper.ledger.LedgerAccount;
import com.tj.crypto.trading.paper.ledger.LedgerPosting;
import com.tj.crypto.trading.paper.persistence.PaperAccountDO;
import com.tj.crypto.trading.paper.persistence.PaperBalanceDO;
import com.tj.crypto.trading.paper.persistence.PaperPositionDO;
import com.tj.crypto.trading.paper.persistence.PaperPositionMapper;
import com.tj.crypto.trading.paper.persistence.PaperTradeDO;
import com.tj.crypto.trading.paper.persistence.PaperTradeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Applies one simulated fill atomically to OMS, balances, positions, ledger, trades and outbox. */
@Service
@RequiredArgsConstructor
class PaperSettlementService {
    private static final MathContext MC = new MathContext(18, RoundingMode.HALF_UP);
    private final PaperAccountLifecycleService accountLifecycleService;
    private final PaperReservationService reservationService;
    private final PaperPositionMapper positionMapper;
    private final PaperTradeMapper tradeMapper;
    private final DoubleEntryLedgerService ledgerService;
    private final OmsPersistenceService omsPersistenceService;
    private final OutboxService outboxService;
    private final PaperMarketDataService marketDataService;

    Order settle(Order order, InstrumentMetadata metadata, int leverage,
                 ExecutionFillPlan plan, String correlationId, long eventTime) {
        if (!plan.hasFill()) return order;
        PaperAccountDO account = accountLifecycleService.lockRunning(null);
        PaperReservationService.ReservationAllocation allocation =
                reservationService.allocate(order.orderId(), plan.filledQuantity());
        if (!account.getAccountId().equals(allocation.accountId())) {
            throw new IllegalStateException("Order reservation belongs to a different account");
        }

        BigDecimal fillPrice = metadata.alignPrice(plan.fillPrice());
        BigDecimal fillQuantity = plan.filledQuantity();
        BigDecimal notional = fillQuantity.multiply(fillPrice, MC)
                .multiply(metadata.contractMultiplier(), MC);
        boolean maker = "MAKER".equals(plan.liquidityRole());
        BigDecimal fee = notional.multiply(metadata.feeRate(maker), MC);
        String executionId = UUID.randomUUID().toString();

        PaperPositionDO position = positionMapper.selectForUpdate(account.getAccountId(),
                metadata.exchange().name(), metadata.marketType().name(), metadata.symbol());
        if (order.reduceOnly()) {
            settleClose(account, order, metadata, position, allocation,
                    fillPrice, fillQuantity, notional, fee, executionId, eventTime);
        } else {
            settleOpen(account, order, metadata, position, allocation,
                    fillPrice, fillQuantity, notional, fee, leverage, executionId, eventTime);
        }

        BigDecimal remainingBefore = order.quantity().subtract(order.filledQuantity());
        OrderEvent fillEvent = fillQuantity.compareTo(remainingBefore) == 0
                ? OrderEvent.filled(order.orderId(), eventTime, fillPrice, fillQuantity)
                : OrderEvent.partiallyFilled(order.orderId(), eventTime, fillPrice, fillQuantity);
        Order updated = OrderStateMachine.transition(order, fillEvent);
        OmsFillMetadata fillMetadata = new OmsFillMetadata(
                account.getAccountId(), correlationId, executionId, fee, metadata.settleAsset(),
                plan.liquidityRole(), plan.fillPrice(), plan.fillPrice(), plan.spreadBps(),
                plan.impactBps(), plan.totalSlippageBps(), leverage, "ISOLATED");
        omsPersistenceService.recordVenue(updated, fillEvent, null, fillMetadata, "PAPER");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountId", account.getAccountId());
        payload.put("orderId", order.orderId());
        payload.put("executionId", executionId);
        payload.put("fillPrice", fillPrice);
        payload.put("fillQuantity", fillQuantity);
        payload.put("fee", fee);
        payload.put("status", updated.status().name());
        outboxService.append("OMS_ORDER", order.orderId(), "PAPER_ORDER_FILLED",
                payload, correlationId, eventTime);
        marketDataService.snapshotEquity(account, eventTime);
        return updated;
    }

    private void settleOpen(PaperAccountDO account, Order order, InstrumentMetadata metadata,
                            PaperPositionDO position,
                            PaperReservationService.ReservationAllocation allocation,
                            BigDecimal fillPrice, BigDecimal quantity, BigDecimal notional,
                            BigDecimal fee, int leverage, String executionId, long eventTime) {
        if (position != null && !position.getSide().equals(order.positionSide().name())) {
            throw new IllegalStateException("Open fill would cross an existing opposite position");
        }
        if (position != null && !position.getStrategyId().equals(order.strategyId())) {
            throw new IllegalArgumentException("A position cannot mix strategy ownership");
        }
        if (metadata.marketType() == MarketType.SPOT) {
            settleSpotBuy(account, order, metadata, position, allocation,
                    fillPrice, quantity, notional, fee, executionId, eventTime);
        } else {
            settlePerpetualOpen(account, order, metadata, position, allocation,
                    fillPrice, quantity, notional, fee, leverage, executionId, eventTime);
        }
    }

    private void settleClose(PaperAccountDO account, Order order, InstrumentMetadata metadata,
                             PaperPositionDO position,
                             PaperReservationService.ReservationAllocation allocation,
                             BigDecimal fillPrice, BigDecimal quantity, BigDecimal notional,
                             BigDecimal fee, String executionId, long eventTime) {
        if (position == null || !position.getSide().equals(order.positionSide().name())) {
            throw new IllegalStateException("Reduce-only fill has no matching position");
        }
        if (quantity.compareTo(position.getQuantity()) > 0) {
            throw new IllegalArgumentException("Close quantity exceeds the current position");
        }
        if (metadata.marketType() == MarketType.SPOT) {
            settleSpotSell(account, order, metadata, position, allocation,
                    fillPrice, quantity, notional, fee, executionId, eventTime);
        } else {
            settlePerpetualClose(account, order, metadata, position, allocation,
                    fillPrice, quantity, fee, executionId, eventTime);
        }
    }

    private void settleSpotBuy(PaperAccountDO account, Order order, InstrumentMetadata metadata,
                               PaperPositionDO position,
                               PaperReservationService.ReservationAllocation allocation,
                               BigDecimal fillPrice, BigDecimal quantity, BigDecimal notional,
                               BigDecimal fee, String executionId, long eventTime) {
        BigDecimal actual = notional.add(fee);
        PaperBalanceDO quote = reservationService.lockOrCreate(account.getAccountId(), metadata.quoteAsset());
        quote.setTotalBalance(quote.getTotalBalance().subtract(actual));
        quote.setAvailableBalance(quote.getAvailableBalance().add(allocation.amount()).subtract(actual));
        quote.setLockedBalance(quote.getLockedBalance().subtract(allocation.amount()));
        reservationService.updateBalance(quote);

        PaperBalanceDO base = reservationService.lockOrCreate(account.getAccountId(), metadata.baseAsset());
        base.setTotalBalance(base.getTotalBalance().add(quantity));
        base.setAvailableBalance(base.getAvailableBalance().add(quantity));
        reservationService.updateBalance(base);

        ledgerService.post(account.getAccountId(), "SPOT_BUY_FILL", "OMS_FILL", executionId,
                eventTime, order.orderId(), List.of(
                        LedgerPosting.debit(LedgerAccount.CASH_AVAILABLE, metadata.quoteAsset(), allocation.amount()),
                        LedgerPosting.credit(LedgerAccount.MARGIN_LOCKED, metadata.quoteAsset(), allocation.amount()),
                        LedgerPosting.debit(LedgerAccount.ASSET_CLEARING, metadata.quoteAsset(), notional),
                        LedgerPosting.debit(LedgerAccount.FEE_EXPENSE, metadata.quoteAsset(), fee),
                        LedgerPosting.credit(LedgerAccount.CASH_AVAILABLE, metadata.quoteAsset(), actual),
                        LedgerPosting.debit(LedgerAccount.CASH_AVAILABLE, metadata.baseAsset(), quantity),
                        LedgerPosting.credit(LedgerAccount.ASSET_CLEARING, metadata.baseAsset(), quantity)));
        upsertOpenPosition(account, order, metadata, position, fillPrice, quantity,
                BigDecimal.ZERO, fee, 1, eventTime);
    }

    private void settleSpotSell(PaperAccountDO account, Order order, InstrumentMetadata metadata,
                                PaperPositionDO position,
                                PaperReservationService.ReservationAllocation allocation,
                                BigDecimal fillPrice, BigDecimal quantity, BigDecimal proceeds,
                                BigDecimal fee, String executionId, long eventTime) {
        PaperBalanceDO base = reservationService.lockOrCreate(account.getAccountId(), metadata.baseAsset());
        base.setTotalBalance(base.getTotalBalance().subtract(quantity));
        base.setAvailableBalance(base.getAvailableBalance().add(allocation.amount()).subtract(quantity));
        base.setLockedBalance(base.getLockedBalance().subtract(allocation.amount()));
        reservationService.updateBalance(base);

        BigDecimal netProceeds = proceeds.subtract(fee);
        PaperBalanceDO quote = reservationService.lockOrCreate(account.getAccountId(), metadata.quoteAsset());
        quote.setTotalBalance(quote.getTotalBalance().add(netProceeds));
        quote.setAvailableBalance(quote.getAvailableBalance().add(netProceeds));
        reservationService.updateBalance(quote);

        List<LedgerPosting> postings = new ArrayList<>();
        postings.add(LedgerPosting.debit(LedgerAccount.CASH_AVAILABLE, metadata.baseAsset(), allocation.amount()));
        postings.add(LedgerPosting.credit(LedgerAccount.MARGIN_LOCKED, metadata.baseAsset(), allocation.amount()));
        postings.add(LedgerPosting.debit(LedgerAccount.ASSET_CLEARING, metadata.baseAsset(), quantity));
        postings.add(LedgerPosting.credit(LedgerAccount.CASH_AVAILABLE, metadata.baseAsset(), quantity));
        postings.add(LedgerPosting.debit(LedgerAccount.CASH_AVAILABLE, metadata.quoteAsset(), proceeds));
        postings.add(LedgerPosting.credit(LedgerAccount.ASSET_CLEARING, metadata.quoteAsset(), proceeds));
        if (fee.signum() > 0) {
            postings.add(LedgerPosting.debit(LedgerAccount.FEE_EXPENSE, metadata.quoteAsset(), fee));
            postings.add(LedgerPosting.credit(LedgerAccount.CASH_AVAILABLE, metadata.quoteAsset(), fee));
        }
        ledgerService.post(account.getAccountId(), "SPOT_SELL_FILL", "OMS_FILL", executionId,
                eventTime, order.orderId(), postings);
        closePosition(account, order, metadata, position, fillPrice, quantity, fee, eventTime);
    }

    private void settlePerpetualOpen(PaperAccountDO account, Order order, InstrumentMetadata metadata,
                                     PaperPositionDO position,
                                     PaperReservationService.ReservationAllocation allocation,
                                     BigDecimal fillPrice, BigDecimal quantity, BigDecimal notional,
                                     BigDecimal fee, int leverage, String executionId, long eventTime) {
        BigDecimal margin = notional.divide(BigDecimal.valueOf(leverage), MC);
        PaperBalanceDO balance = reservationService.lockOrCreate(account.getAccountId(), metadata.settleAsset());
        balance.setTotalBalance(balance.getTotalBalance().subtract(fee));
        balance.setAvailableBalance(balance.getAvailableBalance().add(allocation.amount())
                .subtract(margin).subtract(fee));
        balance.setLockedBalance(balance.getLockedBalance().subtract(allocation.amount()).add(margin));
        reservationService.updateBalance(balance);

        List<LedgerPosting> postings = new ArrayList<>();
        addTransfer(postings, LedgerAccount.CASH_AVAILABLE, LedgerAccount.MARGIN_LOCKED,
                metadata.settleAsset(), allocation.amount());
        addTransfer(postings, LedgerAccount.MARGIN_LOCKED, LedgerAccount.CASH_AVAILABLE,
                metadata.settleAsset(), margin);
        if (fee.signum() > 0) {
            postings.add(LedgerPosting.debit(LedgerAccount.FEE_EXPENSE, metadata.settleAsset(), fee));
            postings.add(LedgerPosting.credit(LedgerAccount.CASH_AVAILABLE, metadata.settleAsset(), fee));
        }
        ledgerService.post(account.getAccountId(), "PERPETUAL_OPEN_FILL", "OMS_FILL", executionId,
                eventTime, order.orderId(), postings);
        upsertOpenPosition(account, order, metadata, position, fillPrice, quantity,
                margin, fee, leverage, eventTime);
    }

    private void settlePerpetualClose(PaperAccountDO account, Order order, InstrumentMetadata metadata,
                                      PaperPositionDO position,
                                      PaperReservationService.ReservationAllocation allocation,
                                      BigDecimal fillPrice, BigDecimal quantity, BigDecimal fee,
                                      String executionId, long eventTime) {
        BigDecimal ratio = quantity.divide(position.getQuantity(), MC);
        BigDecimal marginRelease = position.getInitialMargin().multiply(ratio, MC);
        BigDecimal pnl = calculatePnl(position, fillPrice, quantity);
        PaperBalanceDO balance = reservationService.lockOrCreate(account.getAccountId(), metadata.settleAsset());
        balance.setTotalBalance(balance.getTotalBalance().add(pnl).subtract(fee));
        balance.setAvailableBalance(balance.getAvailableBalance().add(allocation.amount())
                .add(marginRelease).add(pnl).subtract(fee));
        balance.setLockedBalance(balance.getLockedBalance().subtract(allocation.amount()).subtract(marginRelease));
        reservationService.updateBalance(balance);

        List<LedgerPosting> postings = new ArrayList<>();
        addTransfer(postings, LedgerAccount.CASH_AVAILABLE, LedgerAccount.MARGIN_LOCKED,
                metadata.settleAsset(), allocation.amount().add(marginRelease));
        addPnlPostings(postings, metadata.settleAsset(), pnl);
        if (fee.signum() > 0) {
            postings.add(LedgerPosting.debit(LedgerAccount.FEE_EXPENSE, metadata.settleAsset(), fee));
            postings.add(LedgerPosting.credit(LedgerAccount.CASH_AVAILABLE, metadata.settleAsset(), fee));
        }
        ledgerService.post(account.getAccountId(), "PERPETUAL_CLOSE_FILL", "OMS_FILL", executionId,
                eventTime, order.orderId(), postings);
        closePosition(account, order, metadata, position, fillPrice, quantity, fee, eventTime);
    }

    private void upsertOpenPosition(PaperAccountDO account, Order order, InstrumentMetadata metadata,
                                    PaperPositionDO position, BigDecimal fillPrice,
                                    BigDecimal quantity, BigDecimal margin, BigDecimal fee,
                                    int leverage, long eventTime) {
        if (position == null) {
            position = new PaperPositionDO();
            position.setPositionId(UUID.randomUUID().toString());
            position.setAccountId(account.getAccountId());
            position.setExchange(metadata.exchange().name());
            position.setMarketType(metadata.marketType().name());
            position.setSymbol(metadata.symbol());
            position.setSide(order.positionSide().name());
            position.setQuantity(quantity);
            position.setEntryPrice(fillPrice);
            position.setMarkPrice(fillPrice);
            position.setContractMultiplier(metadata.contractMultiplier());
            position.setLeverage(leverage);
            position.setMarginMode("ISOLATED");
            position.setInitialMargin(margin);
            position.setMaintenanceMargin(quantity.multiply(fillPrice, MC)
                    .multiply(metadata.contractMultiplier(), MC)
                    .multiply(metadata.maintenanceMarginRate(), MC));
            position.setOpenFee(fee);
            position.setFunding(BigDecimal.ZERO);
            position.setRealizedPnl(BigDecimal.ZERO);
            position.setUnrealizedPnl(BigDecimal.ZERO);
            position.setStrategyId(order.strategyId());
            position.setOpenOrderId(order.orderId());
            position.setOpenedAtMs(eventTime);
            position.setUpdatedAtMs(eventTime);
            position.setVersion(0L);
            positionMapper.insert(position);
            return;
        }
        BigDecimal oldCost = position.getEntryPrice().multiply(position.getQuantity(), MC);
        BigDecimal newQuantity = position.getQuantity().add(quantity);
        position.setEntryPrice(oldCost.add(fillPrice.multiply(quantity, MC))
                .divide(newQuantity, MC));
        position.setQuantity(newQuantity);
        position.setMarkPrice(fillPrice);
        position.setInitialMargin(position.getInitialMargin().add(margin));
        position.setMaintenanceMargin(newQuantity.multiply(position.getEntryPrice(), MC)
                .multiply(metadata.contractMultiplier(), MC)
                .multiply(metadata.maintenanceMarginRate(), MC));
        position.setOpenFee(position.getOpenFee().add(fee));
        position.setUpdatedAtMs(eventTime);
        positionMapper.updatePosition(position);
    }

    private void closePosition(PaperAccountDO account, Order order, InstrumentMetadata metadata,
                               PaperPositionDO position, BigDecimal fillPrice,
                               BigDecimal quantity, BigDecimal closeFee, long eventTime) {
        BigDecimal ratio = quantity.divide(position.getQuantity(), MC);
        BigDecimal allocatedOpenFee = position.getOpenFee().multiply(ratio, MC);
        BigDecimal allocatedFunding = position.getFunding().multiply(ratio, MC);
        BigDecimal grossPnl = calculatePnl(position, fillPrice, quantity);
        PaperTradeDO trade = new PaperTradeDO();
        trade.setTradeId(UUID.randomUUID().toString());
        trade.setAccountId(account.getAccountId());
        trade.setStrategyId(position.getStrategyId());
        trade.setExchange(position.getExchange());
        trade.setMarketType(position.getMarketType());
        trade.setSymbol(position.getSymbol());
        trade.setSide(position.getSide());
        trade.setQuantity(quantity);
        trade.setEntryPrice(position.getEntryPrice());
        trade.setExitPrice(fillPrice);
        trade.setGrossPnl(grossPnl);
        trade.setOpenFee(allocatedOpenFee);
        trade.setCloseFee(closeFee);
        trade.setFunding(allocatedFunding);
        trade.setNetPnl(grossPnl.subtract(allocatedOpenFee).subtract(closeFee).add(allocatedFunding));
        trade.setOpenOrderId(position.getOpenOrderId());
        trade.setCloseOrderId(order.orderId());
        trade.setOpenedAtMs(position.getOpenedAtMs());
        trade.setClosedAtMs(eventTime);
        trade.setDurationMs(Math.max(0, eventTime - position.getOpenedAtMs()));
        tradeMapper.insert(trade);

        BigDecimal remaining = position.getQuantity().subtract(quantity);
        if (remaining.signum() == 0) {
            positionMapper.deletePosition(position.getPositionId());
        } else {
            position.setQuantity(remaining);
            position.setInitialMargin(position.getInitialMargin().multiply(
                    BigDecimal.ONE.subtract(ratio), MC));
            position.setMaintenanceMargin(position.getMaintenanceMargin().multiply(
                    BigDecimal.ONE.subtract(ratio), MC));
            position.setOpenFee(position.getOpenFee().subtract(allocatedOpenFee));
            position.setFunding(position.getFunding().subtract(allocatedFunding));
            position.setRealizedPnl(position.getRealizedPnl().add(grossPnl));
            position.setMarkPrice(fillPrice);
            position.setUnrealizedPnl(BigDecimal.ZERO);
            position.setUpdatedAtMs(eventTime);
            positionMapper.updatePosition(position);
        }
    }

    private BigDecimal calculatePnl(PaperPositionDO position, BigDecimal exitPrice, BigDecimal quantity) {
        BigDecimal difference = exitPrice.subtract(position.getEntryPrice());
        if ("SHORT".equals(position.getSide())) difference = difference.negate();
        return difference.multiply(quantity, MC).multiply(position.getContractMultiplier(), MC);
    }

    private void addTransfer(List<LedgerPosting> postings, String debitAccount,
                             String creditAccount, String asset, BigDecimal amount) {
        if (amount.signum() == 0) return;
        postings.add(LedgerPosting.debit(debitAccount, asset, amount));
        postings.add(LedgerPosting.credit(creditAccount, asset, amount));
    }

    private void addPnlPostings(List<LedgerPosting> postings, String asset, BigDecimal pnl) {
        if (pnl.signum() > 0) {
            postings.add(LedgerPosting.debit(LedgerAccount.CASH_AVAILABLE, asset, pnl));
            postings.add(LedgerPosting.credit(LedgerAccount.REALIZED_PNL, asset, pnl));
        } else if (pnl.signum() < 0) {
            postings.add(LedgerPosting.debit(LedgerAccount.REALIZED_LOSS, asset, pnl.abs()));
            postings.add(LedgerPosting.credit(LedgerAccount.CASH_AVAILABLE, asset, pnl.abs()));
        }
    }
}
