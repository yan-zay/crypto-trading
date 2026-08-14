package com.tj.crypto.factor.derivative;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.InstrumentId;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.event.MarketEventBus;
import com.tj.crypto.factor.core.Factor;
import com.tj.crypto.factor.core.FactorCalculator;
import com.tj.crypto.marketdata.model.OpenInterestEvent;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 持仓量变化率因子。
 * 计算最近 N 次持仓量的百分比变化。
 *
 * 正值：持仓量增加（资金流入，趋势可能延续）
 * 负值：持仓量减少（资金流出，趋势可能反转）
 */
@Slf4j
@Component
public class OpenInterestChangeFactor implements FactorCalculator {

    private final MarketEventBus eventBus;
    private final ConcurrentHashMap<InstrumentId, List<OiObservation>> oiHistory = new ConcurrentHashMap<>();
    private static final int HISTORY_SIZE = 100;

    public OpenInterestChangeFactor(MarketEventBus eventBus) {
        this.eventBus = eventBus;
    }

    @PostConstruct
    public void init() {
        eventBus.subscribe(OpenInterestEvent.class, this::onOpenInterest);
    }

    private void onOpenInterest(OpenInterestEvent event) {
        InstrumentId key = event.instrument().id();
        oiHistory.compute(key, (k, v) -> {
            List<OiObservation> list = v != null ? v : Collections.synchronizedList(new ArrayList<>());
            synchronized (list) {
                list.add(new OiObservation(event.metadata().exchangeTimestamp(), event.openInterestUsd()));
                while (list.size() > HISTORY_SIZE) {
                    list.remove(0);
                }
            }
            return list;
        });
    }

    @Override
    public String name() {
        return "OI_CHANGE_PCT";
    }

    @Override
    public Factor calculate(Instrument instrument, Timeframe timeframe) {
        return calculateAsOf(instrument, timeframe, Long.MAX_VALUE);
    }

    @Override
    public Factor calculateAsOf(Instrument instrument, Timeframe timeframe, long asOfTimestamp) {
        List<OiObservation> history = oiHistory.get(instrument.id());
        if (history == null) return Factor.warmup(name());
        synchronized (history) {
            List<OiObservation> eligible = history.stream()
                    .filter(item -> item.timestamp() <= asOfTimestamp)
                    .toList();
            if (eligible.size() < 2) return Factor.warmup(name());
            OiObservation latest = eligible.get(eligible.size() - 1);
            OiObservation previous = eligible.get(eligible.size() - 2);
            if (previous.value().compareTo(BigDecimal.ZERO) == 0) {
                return Factor.of(name(), BigDecimal.ZERO, latest.timestamp());
            }
            BigDecimal changePct = latest.value().subtract(previous.value())
                    .divide(previous.value(), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            return Factor.of(name(), changePct, latest.timestamp());
        }
    }

    private record OiObservation(long timestamp, BigDecimal value) {}
}
