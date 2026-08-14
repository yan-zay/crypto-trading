package com.tj.crypto.trading.paper;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.reliability.outbox.OutboxService;
import com.tj.crypto.trading.instrument.InstrumentMetadata;
import com.tj.crypto.trading.instrument.InstrumentMetadataService;
import com.tj.crypto.trading.paper.ledger.DoubleEntryLedgerService;
import com.tj.crypto.trading.paper.ledger.LedgerAccount;
import com.tj.crypto.trading.paper.ledger.LedgerPosting;
import com.tj.crypto.trading.paper.persistence.PaperAccountDO;
import com.tj.crypto.trading.paper.persistence.PaperBalanceDO;
import com.tj.crypto.trading.paper.persistence.PaperFundingSettlementDO;
import com.tj.crypto.trading.paper.persistence.PaperFundingSettlementMapper;
import com.tj.crypto.trading.paper.persistence.PaperPositionDO;
import com.tj.crypto.trading.paper.persistence.PaperPositionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/** Idempotent funding settlement for linear perpetual paper positions. */
@Service
@RequiredArgsConstructor
public class PaperFundingService {
    private static final MathContext MC = new MathContext(18, RoundingMode.HALF_UP);
    private final PaperAccountLifecycleService lifecycleService;
    private final PaperPositionMapper positionMapper;
    private final PaperReservationService reservationService;
    private final PaperFundingSettlementMapper settlementMapper;
    private final InstrumentMetadataService metadataService;
    private final DoubleEntryLedgerService ledgerService;
    private final PaperMarketDataService marketDataService;
    private final OutboxService outboxService;

    @Transactional
    public PaperFundingSettlementDO apply(String accountId, Exchange exchange, String symbol,
                                          BigDecimal fundingRate, String eventId, long eventTime) {
        if (eventId == null || eventId.isBlank()) throw new IllegalArgumentException("eventId is required");
        if (fundingRate == null) throw new IllegalArgumentException("fundingRate is required");
        if (settlementMapper.exists(eventId) > 0) {
            PaperFundingSettlementDO existing = settlementMapper.selectById(eventId);
            if (!accountId.equals(existing.getAccountId())) {
                throw new IllegalArgumentException("funding event belongs to another account");
            }
            return existing;
        }
        PaperAccountDO account = lifecycleService.lockRunning(accountId);
        InstrumentMetadata metadata = metadataService.require(exchange, MarketType.PERPETUAL,
                symbol, eventTime);
        PaperPositionDO position = positionMapper.selectForUpdate(account.getAccountId(),
                exchange.name(), MarketType.PERPETUAL.name(), metadata.symbol());
        if (position == null) throw new IllegalStateException("No perpetual position for funding settlement");
        BigDecimal notional = position.getQuantity().multiply(position.getMarkPrice(), MC)
                .multiply(position.getContractMultiplier(), MC);
        BigDecimal raw = notional.multiply(fundingRate, MC);
        BigDecimal adjustment = "LONG".equals(position.getSide()) ? raw.negate() : raw;
        PaperBalanceDO balance = reservationService.lockOrCreate(account.getAccountId(), metadata.settleAsset());
        if (balance.getAvailableBalance().add(adjustment).signum() < 0) {
            throw new IllegalStateException("Funding debit exceeds available settlement balance");
        }
        balance.setTotalBalance(balance.getTotalBalance().add(adjustment));
        balance.setAvailableBalance(balance.getAvailableBalance().add(adjustment));
        reservationService.updateBalance(balance);
        position.setFunding(position.getFunding().add(adjustment));
        position.setUpdatedAtMs(eventTime);
        positionMapper.updatePosition(position);

        List<LedgerPosting> postings;
        if (adjustment.signum() > 0) {
            postings = List.of(
                        LedgerPosting.debit(LedgerAccount.CASH_AVAILABLE, metadata.settleAsset(), adjustment),
                        LedgerPosting.credit(LedgerAccount.FUNDING_INCOME, metadata.settleAsset(), adjustment));
        } else if (adjustment.signum() < 0) {
            postings = List.of(
                        LedgerPosting.debit(LedgerAccount.FUNDING_EXPENSE, metadata.settleAsset(), adjustment.abs()),
                        LedgerPosting.credit(LedgerAccount.CASH_AVAILABLE, metadata.settleAsset(), adjustment.abs()));
        } else {
            postings = List.of();
        }
        if (adjustment.signum() != 0) {
            ledgerService.post(account.getAccountId(), "FUNDING_SETTLEMENT", "FUNDING_EVENT",
                    eventId, eventTime, metadata.symbol(), postings);
        }
        PaperFundingSettlementDO settlement = new PaperFundingSettlementDO();
        settlement.setFundingEventId(eventId);
        settlement.setAccountId(account.getAccountId());
        settlement.setPositionId(position.getPositionId());
        settlement.setExchange(exchange.name());
        settlement.setSymbol(metadata.symbol());
        settlement.setFundingRate(fundingRate);
        settlement.setMarkPrice(position.getMarkPrice());
        settlement.setFundingAmount(adjustment);
        settlement.setEventTimeMs(eventTime);
        settlementMapper.insert(settlement);
        outboxService.append("PAPER_POSITION", position.getPositionId(), "PAPER_FUNDING_SETTLED",
                Map.of("accountId", accountId, "positionId", position.getPositionId(),
                        "fundingRate", fundingRate, "fundingAmount", adjustment), eventId, eventTime);
        marketDataService.snapshotEquity(account, eventTime);
        return settlement;
    }
}
