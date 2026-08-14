package com.tj.crypto.factor.derivative;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.InstrumentId;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.factor.core.Factor;
import com.tj.crypto.factor.core.FactorCalculator;
import com.tj.crypto.marketdata.model.FundingRateEvent;
import com.tj.crypto.marketdata.model.MarketEvent;
import com.tj.crypto.event.MarketEventBus;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 资金费率变化率因子。
 * 计算最近 N 次资金费率的变化趋势。
 *
 * 正值：资金费率上升（多头支付空头，市场偏多）
 * 负值：资金费率下降（空头支付多头，市场偏空）
 */
@Slf4j
@Component
public class FundingRateChangeFactor implements FactorCalculator {

    private final MarketEventBus eventBus;
    private final ConcurrentHashMap<InstrumentId, List<RateObservation>> rateHistory = new ConcurrentHashMap<>();
    private static final int HISTORY_SIZE = 24;

    public FundingRateChangeFactor(MarketEventBus eventBus) {
        this.eventBus = eventBus;
    }

    @PostConstruct
    public void init() {
        eventBus.subscribe(FundingRateEvent.class, this::onFundingRate);
    }

    private void onFundingRate(FundingRateEvent event) {
        InstrumentId key = event.instrument().id();
        rateHistory.compute(key, (k, v) -> {
            List<RateObservation> list = v != null ? v : Collections.synchronizedList(new ArrayList<>());
            synchronized (list) {
                list.add(new RateObservation(event.metadata().exchangeTimestamp(), event.fundingRate()));
                while (list.size() > HISTORY_SIZE) {
                    list.remove(0);
                }
            }
            return list;
        });
    }

    @Override
    public String name() {
        return "FUNDING_RATE_CHANGE";
    }

    @Override
    public Factor calculate(Instrument instrument, Timeframe timeframe) {
        return calculateAsOf(instrument, timeframe, Long.MAX_VALUE);
    }

    @Override
    public Factor calculateAsOf(Instrument instrument, Timeframe timeframe, long asOfTimestamp) {
        List<RateObservation> rates = rateHistory.get(instrument.id());
        if (rates == null) return Factor.warmup(name());
        synchronized (rates) {
            List<RateObservation> eligible = rates.stream()
                    .filter(rate -> rate.timestamp() <= asOfTimestamp)
                    .toList();
            if (eligible.size() < 2) return Factor.warmup(name());
            RateObservation latest = eligible.get(eligible.size() - 1);
            RateObservation previous = eligible.get(eligible.size() - 2);
            return Factor.of(name(), latest.value().subtract(previous.value()), latest.timestamp());
        }
    }

    private record RateObservation(long timestamp, BigDecimal value) {}
}
