package com.tj.crypto.strategy.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内信号收集器实现。
 * 按策略名称分组存储信号。线程安全。
 */
@Slf4j
@Component
public class InMemorySignalCollector implements SignalCollector {

    private static final int MAX_SIGNALS_PER_STRATEGY = 10_000;
    private final ConcurrentHashMap<String, Deque<SignalStore>> store = new ConcurrentHashMap<>();

    @Override
    public void collect(SignalEvent signal) {
        Deque<SignalStore> signals = store.computeIfAbsent(
                signal.strategyName(), ignored -> new ArrayDeque<>());
        synchronized (signals) {
            if (signals.size() >= MAX_SIGNALS_PER_STRATEGY) signals.removeFirst();
            signals.addLast(new SignalStore(signal));
        }
        log.debug("Signal collected: {} {} {}", signal.strategyName(), signal.type(), signal.reason());
    }

    @Override
    public List<SignalEvent> getSignals(String strategyName) {
        Deque<SignalStore> list = store.get(strategyName);
        if (list == null) return List.of();
        synchronized (list) {
            return list.stream().map(SignalStore::signal).toList();
        }
    }

    @Override
    public List<SignalEvent> getSignals(String strategyName, long from, long to) {
        Deque<SignalStore> list = store.get(strategyName);
        if (list == null) return List.of();
        synchronized (list) {
            return list.stream()
                    .map(SignalStore::signal)
                    .filter(s -> s.timestamp() >= from && s.timestamp() <= to)
                    .toList();
        }
    }

    @Override
    public List<SignalEvent> getAllSignals() {
        List<SignalEvent> all = new ArrayList<>();
        for (Deque<SignalStore> list : store.values()) {
            synchronized (list) {
                list.stream().map(SignalStore::signal).forEach(all::add);
            }
        }
        return all;
    }

    @Override
    public void clear() {
        store.clear();
    }

    private record SignalStore(SignalEvent signal) {}
}
