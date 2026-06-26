package com.tj.crypto.factor.cache;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.event.MarketEventBus;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内 Bar 缓存实现。
 * 自动订阅 MarketEventBus 的 BarEvent，按 Instrument+Timeframe 缓存。
 *
 * 设计决策：
 * - 使用 ArrayDeque 作为有界队列（FIFO，超过容量时移除最旧的 bar）
 * - 自动订阅事件总线，无需手动添加
 * - 线程安全：ConcurrentHashMap + synchronized deque 操作
 */
@Slf4j
@Component
public class InMemoryBarCache implements BarCache {

    /** 每个 Instrument+Timeframe 组合的最大缓存数量 */
    private static final int MAX_BARS_PER_KEY = 500;

    private final ConcurrentHashMap<String, List<BarEvent>> cache = new ConcurrentHashMap<>();
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
        String key = buildKey(bar.instrument(), bar.timeframe());
        cache.compute(key, (k, v) -> {
            List<BarEvent> list = v != null ? v : Collections.synchronizedList(new ArrayList<>());
            list.add(bar);
            // 超过容量时移除最旧的
            while (list.size() > MAX_BARS_PER_KEY) {
                list.remove(0);
            }
            return list;
        });
    }

    @Override
    public List<BarEvent> getBars(Instrument instrument, Timeframe timeframe, int count) {
        String key = buildKey(instrument, timeframe);
        List<BarEvent> list = cache.get(key);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        synchronized (list) {
            int size = list.size();
            int fromIndex = Math.max(0, size - count);
            return new ArrayList<>(list.subList(fromIndex, size));
        }
    }

    @Override
    public int size(Instrument instrument, Timeframe timeframe) {
        String key = buildKey(instrument, timeframe);
        List<BarEvent> list = cache.get(key);
        return list != null ? list.size() : 0;
    }

    private String buildKey(Instrument instrument, Timeframe timeframe) {
        return instrument.exchange().getCode() + ":" + instrument.symbol() + ":" + timeframe.getCode();
    }
}
