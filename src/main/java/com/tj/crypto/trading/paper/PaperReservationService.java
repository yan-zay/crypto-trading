package com.tj.crypto.trading.paper;

import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.TradeSide;
import com.tj.crypto.trading.paper.ledger.DoubleEntryLedgerService;
import com.tj.crypto.trading.paper.ledger.LedgerAccount;
import com.tj.crypto.trading.paper.ledger.LedgerPosting;
import com.tj.crypto.trading.paper.persistence.PaperBalanceDO;
import com.tj.crypto.trading.paper.persistence.PaperBalanceMapper;
import com.tj.crypto.trading.paper.persistence.PaperOrderReservationDO;
import com.tj.crypto.trading.paper.persistence.PaperOrderReservationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

/** Freezes, proportionally consumes and releases paper order buying power. */
@Service
@RequiredArgsConstructor
class PaperReservationService {
    private static final MathContext MC = new MathContext(18, RoundingMode.HALF_UP);
    private final PaperBalanceMapper balanceMapper;
    private final PaperOrderReservationMapper reservationMapper;
    private final DoubleEntryLedgerService ledgerService;

    PaperOrderReservationDO reserve(PaperOrderIntent intent, String orderId) {
        ReservationRequirement requirement = requirement(intent);
        PaperBalanceDO balance = lockOrCreate(intent.accountId(), requirement.asset());
        if (balance.getAvailableBalance().compareTo(requirement.amount()) < 0) {
            throw new IllegalArgumentException("Insufficient available " + requirement.asset()
                    + " for order reservation: required=" + requirement.amount()
                    + ", available=" + balance.getAvailableBalance());
        }
        balance.setAvailableBalance(balance.getAvailableBalance().subtract(requirement.amount()));
        balance.setLockedBalance(balance.getLockedBalance().add(requirement.amount()));
        updateBalance(balance);
        ledgerService.post(intent.accountId(), "ORDER_RESERVATION", "OMS_ORDER", orderId,
                intent.timestamp(), requirement.type(), List.of(
                        LedgerPosting.debit(LedgerAccount.MARGIN_LOCKED, requirement.asset(), requirement.amount()),
                        LedgerPosting.credit(LedgerAccount.CASH_AVAILABLE, requirement.asset(), requirement.amount())));

        PaperOrderReservationDO reservation = new PaperOrderReservationDO();
        reservation.setOrderId(orderId);
        reservation.setAccountId(intent.accountId());
        reservation.setAsset(requirement.asset());
        reservation.setReservationType(requirement.type());
        reservation.setOriginalAmount(requirement.amount());
        reservation.setRemainingAmount(requirement.amount());
        reservation.setOriginalQuantity(intent.quantity());
        reservation.setRemainingQuantity(intent.quantity());
        reservationMapper.insert(reservation);
        return reservation;
    }

    ReservationAllocation allocate(String orderId, BigDecimal fillQuantity) {
        PaperOrderReservationDO reservation = reservationMapper.selectForUpdate(orderId);
        if (reservation == null) throw new IllegalStateException("Missing order reservation: " + orderId);
        if (fillQuantity.signum() <= 0 || fillQuantity.compareTo(reservation.getRemainingQuantity()) > 0) {
            throw new IllegalArgumentException("Invalid reservation fill quantity");
        }
        boolean finalAllocation = fillQuantity.compareTo(reservation.getRemainingQuantity()) == 0;
        BigDecimal amount = finalAllocation ? reservation.getRemainingAmount()
                : reservation.getRemainingAmount().multiply(fillQuantity, MC)
                .divide(reservation.getRemainingQuantity(), MC);
        reservation.setRemainingAmount(reservation.getRemainingAmount().subtract(amount));
        reservation.setRemainingQuantity(reservation.getRemainingQuantity().subtract(fillQuantity));
        if (finalAllocation) reservationMapper.delete(orderId);
        else reservationMapper.update(reservation);
        return new ReservationAllocation(reservation.getAccountId(), reservation.getAsset(),
                reservation.getReservationType(), amount, fillQuantity);
    }

    BigDecimal release(String orderId, long timestamp) {
        PaperOrderReservationDO reservation = reservationMapper.selectForUpdate(orderId);
        if (reservation == null) return BigDecimal.ZERO;
        BigDecimal amount = reservation.getRemainingAmount();
        PaperBalanceDO balance = lockOrCreate(reservation.getAccountId(), reservation.getAsset());
        balance.setAvailableBalance(balance.getAvailableBalance().add(amount));
        balance.setLockedBalance(balance.getLockedBalance().subtract(amount));
        updateBalance(balance);
        if (amount.signum() > 0) {
            ledgerService.post(reservation.getAccountId(), "ORDER_RESERVATION_RELEASE",
                    "OMS_ORDER", orderId + ":RELEASE", timestamp, "Cancel/expire reservation release",
                    List.of(
                            LedgerPosting.debit(LedgerAccount.CASH_AVAILABLE, reservation.getAsset(), amount),
                            LedgerPosting.credit(LedgerAccount.MARGIN_LOCKED, reservation.getAsset(), amount)));
        }
        reservationMapper.delete(orderId);
        return amount;
    }

    PaperBalanceDO lockOrCreate(String accountId, String asset) {
        PaperBalanceDO balance = balanceMapper.selectForUpdate(accountId, asset);
        if (balance != null) return balance;
        balance = new PaperBalanceDO();
        balance.setAccountId(accountId);
        balance.setAsset(asset);
        balance.setTotalBalance(BigDecimal.ZERO);
        balance.setAvailableBalance(BigDecimal.ZERO);
        balance.setLockedBalance(BigDecimal.ZERO);
        balanceMapper.insert(balance);
        return balanceMapper.selectForUpdate(accountId, asset);
    }

    void updateBalance(PaperBalanceDO balance) {
        if (balance.getTotalBalance().signum() < 0 || balance.getAvailableBalance().signum() < 0
                || balance.getLockedBalance().signum() < 0) {
            throw new IllegalStateException("Paper balance invariant violated for " + balance.getAsset());
        }
        if (balance.getAvailableBalance().add(balance.getLockedBalance())
                .compareTo(balance.getTotalBalance()) > 0) {
            throw new IllegalStateException("Available + locked exceeds total for " + balance.getAsset());
        }
        if (balanceMapper.update(balance) != 1) {
            throw new IllegalStateException("Paper balance update failed for " + balance.getAsset());
        }
    }

    private ReservationRequirement requirement(PaperOrderIntent intent) {
        BigDecimal notional = intent.quantity().multiply(intent.orderPrice(), MC)
                .multiply(intent.metadata().contractMultiplier(), MC);
        boolean maker = intent.orderType() == com.tj.crypto.execution.model.OrderType.LIMIT;
        BigDecimal fee = notional.multiply(intent.metadata().feeRate(maker), MC);
        if (intent.metadata().marketType() == MarketType.SPOT) {
            if (intent.tradeSide() == TradeSide.BUY) {
                return new ReservationRequirement(intent.metadata().quoteAsset(),
                        notional.add(fee), "SPOT_BUY_QUOTE");
            }
            return new ReservationRequirement(intent.metadata().baseAsset(), intent.quantity(),
                    "SPOT_SELL_BASE");
        }
        if (intent.action() == PaperOrderAction.OPEN) {
            BigDecimal margin = notional.divide(BigDecimal.valueOf(intent.leverage()), MC);
            return new ReservationRequirement(intent.metadata().settleAsset(), margin.add(fee),
                    "PERPETUAL_OPEN_MARGIN_FEE");
        }
        return new ReservationRequirement(intent.metadata().settleAsset(), fee,
                "PERPETUAL_CLOSE_FEE");
    }

    record ReservationAllocation(String accountId, String asset, String type,
                                 BigDecimal amount, BigDecimal quantity) {}

    private record ReservationRequirement(String asset, BigDecimal amount, String type) {}
}
