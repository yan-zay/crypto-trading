package com.tj.crypto.strategy.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内信号收集器实现。
 * 按策略名称分组存储信号。线程安全。
 */
@Slf4j
@Component
public class InMemorySignalCollector implements SignalCollector {

    private final ConcurrentHashMap<String, List<SignalStore>> store = new ConcurrentHashMap<>();

    @Override
    public void collect(SignalEvent signal) {
        // 使用 synchronizedList 确保并发安全
        store.computeIfAbsent(signal.strategyName(), k -> Collections.synchronizedList(new ArrayList<>()))
                .add(new SignalStore(signal));
        log.debug("Signal collected: {} {} {}", signal.strategyName(), signal.type(), signal.reason());
    }

    @Override
    public List<SignalEvent> getSignals(String strategyName) {
        List<SignalStore> list = store.get(strategyName);
        if (list == null) return List.of();
        synchronized (list) {
            return list.stream().map(SignalStore::signal).toList();
        }
    }

    @Override
    public List<SignalEvent> getSignals(String strategyName, long from, long to) {
        List<SignalStore> list = store.get(strategyName);
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
        for (List<SignalStore> list : store.values()) {
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
