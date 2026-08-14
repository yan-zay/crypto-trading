package com.tj.crypto.factor.cache;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketSeriesKey;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.event.MarketEventBus;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * 进程内 Bar 缓存实现。
 * 自动订阅 MarketEventBus 的 BarEvent，按 Instrument+Timeframe 缓存。
 *
 * 设计决策：
 * - finalized 与 forming K 线物理隔离，指标永远不会读取未收盘快照
 * - finalized 序列按 openTime 幂等 upsert，重连和重复推送不会增加 bar 数量
 * - 自动订阅事件总线，无需手动添加
 * - 线程安全：ConcurrentHashMap + ConcurrentSkipListMap
 */
@Slf4j
@Component
public class InMemoryBarCache implements BarCache {

    /** 每个 Instrument+Timeframe 组合的最大缓存数量 */
    private static final int MAX_BARS_PER_KEY = 500;

    private final ConcurrentHashMap<MarketSeriesKey, ConcurrentNavigableMap<Long, BarEvent>> finalizedBars =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<MarketSeriesKey, BarEvent> formingBars = new ConcurrentHashMap<>();
    private final MarketEventBus eventBus;

    public InMemoryBarCache(MarketEventBus eventBus) {
        this.eventBus = eventBus;
    }

    @PostConstruct
    public void init() {
        eventBus.subscribe(BarEvent.class, this::addBar);
        log.info("InMemoryBarCache subscribed to BarEvent");
    }

    @Override
    public void addBar(BarEvent bar) {
        MarketSeriesKey key = MarketSeriesKey.of(bar.instrument(), bar.timeframe());
        long openTime = bar.metadata().exchangeTimestamp();

        if (!bar.closed()) {
            formingBars.compute(key, (ignored, current) -> {
                if (current == null || current.metadata().exchangeTimestamp() < openTime) {
                    return bar;
                }
                return current.metadata().exchangeTimestamp() == openTime
                        && current.metadata().receivedTimestamp() <= bar.metadata().receivedTimestamp()
                        ? bar : current;
            });
            return;
        }

        formingBars.computeIfPresent(key, (ignored, current) ->
                current.metadata().exchangeTimestamp() == openTime ? null : current);
        ConcurrentNavigableMap<Long, BarEvent> series = finalizedBars.computeIfAbsent(
                key, ignored -> new ConcurrentSkipListMap<>());
        series.put(openTime, bar);
        while (series.size() > MAX_BARS_PER_KEY) {
            series.pollFirstEntry();
        }
    }

    @Override
    public List<BarEvent> getBars(Instrument instrument, Timeframe timeframe, int count) {
        return getBarsAsOf(instrument, timeframe, Long.MAX_VALUE, count);
    }

    @Override
    public List<BarEvent> getBarsAsOf(Instrument instrument, Timeframe timeframe,
                                      long asOfTimestamp, int count) {
        if (count <= 0) {
            return List.of();
        }
        ConcurrentNavigableMap<Long, BarEvent> series = finalizedBars.get(
                MarketSeriesKey.of(instrument, timeframe));
        if (series == null || series.isEmpty()) {
            return List.of();
        }
        List<BarEvent> result = new ArrayList<>(series.headMap(asOfTimestamp, true)
                .descendingMap()
                .values()
                .stream()
                .limit(count)
                .toList());
        Collections.reverse(result);
        return result;
    }

    @Override
    public Optional<BarEvent> getFormingBar(Instrument instrument, Timeframe timeframe) {
        return Optional.ofNullable(formingBars.get(MarketSeriesKey.of(instrument, timeframe)));
    }

    @Override
    public int size(Instrument instrument, Timeframe timeframe) {
        ConcurrentNavigableMap<Long, BarEvent> series = finalizedBars.get(
                MarketSeriesKey.of(instrument, timeframe));
        return series != null ? series.size() : 0;
    }
}
