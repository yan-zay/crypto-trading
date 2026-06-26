package com.tj.crypto.strategy.portfolio;

import com.tj.crypto.marketdata.model.MarketEvent;
import com.tj.crypto.strategy.core.SignalEvent;
import com.tj.crypto.strategy.core.Strategy;
import com.tj.crypto.strategy.core.StrategyContext;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 多策略组合管理器。
 * 管理多个策略及其资金分配比例，统一分发事件并收集信号。
 *
 * <p>设计要点：
 * <ul>
 *   <li>资金分配总和必须为 100%（允许 ±0.01% 精度误差）</li>
 *   <li>每个策略独立运行，信号互不干扰</li>
 *   <li>不可变：添加策略后分配比例不可修改</li>
 *   <li>线程安全：内部使用 synchronized 保护状态</li>
 * </ul>
 *
 * <p>典型用法：
 * <pre>
 *   StrategyPortfolio portfolio = new StrategyPortfolio();
 *   portfolio.addStrategy(macdStrategy, new BigDecimal("60"));
 *   portfolio.addStrategy(rsiStrategy, new BigDecimal("40"));
 *
 *   List&lt;SignalEvent&gt; signals = portfolio.onEvent(event, context);
 * </pre>
 */
@Slf4j
public class StrategyPortfolio {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal ALLOCATION_TOLERANCE = BigDecimal.valueOf(0.01);
    private static final int SCALE = 2;

    /** 策略 → 资金分配比例（%） */
    private final Map<Strategy, BigDecimal> strategyAllocations = new LinkedHashMap<>();

    /**
     * 添加策略及其资金分配比例。
     *
     * @param strategy     策略实例
     * @param allocationPct 资金分配比例（百分比，如 60 表示 60%）
     * @throws IllegalArgumentException 如果策略为 null、比例非正、或添加后总和超过 100%
     */
    public synchronized void addStrategy(Strategy strategy, BigDecimal allocationPct) {
        if (strategy == null) {
            throw new IllegalArgumentException("Strategy must not be null");
        }
        if (allocationPct == null || allocationPct.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Allocation percentage must be positive, got: " + allocationPct);
        }
        if (strategyAllocations.containsKey(strategy)) {
            throw new IllegalArgumentException("Strategy already registered: " + strategy.name());
        }

        BigDecimal currentTotal = getTotalAllocation();
        BigDecimal newTotal = currentTotal.add(allocationPct);

        if (newTotal.subtract(HUNDRED).compareTo(ALLOCATION_TOLERANCE) > 0) {
            throw new IllegalArgumentException(
                    String.format("Total allocation would exceed 100%%: current=%.2f%%, adding=%.2f%%, total=%.2f%%",
                            currentTotal, allocationPct, newTotal));
        }

        strategyAllocations.put(strategy, allocationPct);
        log.info("Added strategy '{}' with {}% allocation (total: {}%)",
                strategy.name(), allocationPct, newTotal.setScale(SCALE, RoundingMode.HALF_UP));
    }

    /**
     * 向所有注册策略分发事件，收集产生的信号。
     *
     * @param event   市场事件
     * @param context 策略上下文
     * @return 所有策略产生的信号列表（不含 null）
     */
    public synchronized List<SignalEvent> onEvent(MarketEvent event, StrategyContext context) {
        List<SignalEvent> signals = new ArrayList<>();

        for (Map.Entry<Strategy, BigDecimal> entry : strategyAllocations.entrySet()) {
            Strategy strategy = entry.getKey();

            if (!strategy.listenedEvents().contains(event.getClass())) {
                continue;
            }

            try {
                SignalEvent signal = strategy.onEvent(event, context);
                if (signal != null) {
                    signals.add(signal);
                }
            } catch (Exception e) {
                log.error("Strategy '{}' failed to process event: {}",
                        strategy.name(), e.getMessage(), e);
            }
        }

        return signals;
    }

    /**
     * 获取所有监听的事件类型（所有策略的并集）。
     */
    public synchronized Set<Class<? extends MarketEvent>> getListenEventTypes() {
        return strategyAllocations.keySet().stream()
                .flatMap(s -> s.listenedEvents().stream())
                .collect(Collectors.toSet());
    }

    /**
     * 获取当前分配总和。
     */
    public synchronized BigDecimal getTotalAllocation() {
        return strategyAllocations.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 验证分配总和是否为 100%。
     *
     * @return 如果总和为 100%（允许 ±0.01% 误差）返回 true
     */
    public synchronized boolean isAllocationComplete() {
        BigDecimal total = getTotalAllocation();
        return total.subtract(HUNDRED).abs().compareTo(ALLOCATION_TOLERANCE) <= 0;
    }

    /**
     * 获取所有策略及其分配比例的不可变视图。
     */
    public synchronized Map<Strategy, BigDecimal> getAllocations() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(strategyAllocations));
    }

    /**
     * 获取注册的策略数量。
     */
    public synchronized int size() {
        return strategyAllocations.size();
    }

    /**
     * 获取所有注册策略的名称。
     */
    public synchronized List<String> getStrategyNames() {
        return strategyAllocations.keySet().stream()
                .map(Strategy::name)
                .collect(Collectors.toList());
    }
}
