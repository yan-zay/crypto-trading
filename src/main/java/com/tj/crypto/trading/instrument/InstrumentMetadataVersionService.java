package com.tj.crypto.trading.instrument;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/** Applies source observations as slowly changing dimension type-2 records. */
@Service
@RequiredArgsConstructor
public class InstrumentMetadataVersionService {
    private final InstrumentMetadataMapper mapper;

    @Transactional
    public boolean apply(InstrumentRuleSnapshot snapshot, long observedAt) {
        InstrumentMetadataDO current = mapper.selectActive(snapshot.exchange().name(),
                snapshot.marketType().name(), snapshot.symbol(), observedAt);
        if (current != null && equivalent(current, snapshot)) return false;
        if (current != null && mapper.closeVersion(current.getMetadataId(), observedAt) != 1) {
            throw new IllegalStateException("Instrument metadata was concurrently updated");
        }
        InstrumentMetadataDO next = toDO(snapshot, current, observedAt);
        mapper.insert(next);
        return true;
    }

    private InstrumentMetadataDO toDO(InstrumentRuleSnapshot source,
                                      InstrumentMetadataDO previous, long observedAt) {
        InstrumentMetadataDO target = new InstrumentMetadataDO();
        target.setExchange(source.exchange().name());
        target.setMarketType(source.marketType().name());
        target.setSymbol(source.symbol());
        target.setVenueSymbol(source.venueSymbol());
        target.setBaseAsset(source.baseAsset());
        target.setQuoteAsset(source.quoteAsset());
        target.setSettleAsset(source.settleAsset());
        target.setInstrumentStatus(source.status());
        target.setTickSize(source.tickSize());
        target.setStepSize(source.stepSize());
        target.setMinQuantity(source.minQuantity());
        target.setMaxQuantity(source.maxQuantity());
        target.setMinNotional(source.minNotional() == null && previous != null
                ? previous.getMinNotional() : zero(source.minNotional()));
        target.setContractMultiplier(source.contractMultiplier());
        target.setMakerFeeRate(previous == null ? BigDecimal.ZERO : previous.getMakerFeeRate());
        target.setTakerFeeRate(previous == null ? BigDecimal.ZERO : previous.getTakerFeeRate());
        target.setMaxLeverage(previous == null ? 1 : previous.getMaxLeverage());
        target.setMaintenanceMarginRate(previous == null ? BigDecimal.ZERO : previous.getMaintenanceMarginRate());
        target.setFundingIntervalHours(previous == null ? null : previous.getFundingIntervalHours());
        target.setValidFromMs(observedAt);
        target.setSourceVersion(source.sourceVersion());
        return target;
    }

    private boolean equivalent(InstrumentMetadataDO current, InstrumentRuleSnapshot next) {
        return current.getVenueSymbol().equals(next.venueSymbol())
                && current.getInstrumentStatus().equals(next.status())
                && equal(current.getTickSize(), next.tickSize())
                && equal(current.getStepSize(), next.stepSize())
                && equal(current.getMinQuantity(), next.minQuantity())
                && equal(current.getMaxQuantity(), next.maxQuantity())
                && (next.minNotional() == null || equal(current.getMinNotional(), next.minNotional()))
                && equal(current.getContractMultiplier(), next.contractMultiplier());
    }

    private boolean equal(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
