package com.tj.crypto.strategy.core;

import com.tj.crypto.strategy.config.StrategyProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 策略管理器。
 * 管理所有策略的启用/禁用状态，支持运行时动态切换。
 *
 * 初始状态从 application.yml 的 crypto.strategy.configs.{name}.enabled 读取，
 * 未配置的策略默认启用。
 *
 * 线程安全：使用 ConcurrentHashMap 存储状态。
 */
@Slf4j
@Component
public class StrategyManager {

    private final Map<String, Strategy> strategyMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> enabledMap = new ConcurrentHashMap<>();

    /**
     * 构造函数。
     * 注入所有 Strategy Bean，并根据配置初始化启用/禁用状态。
     *
     * @param strategies        所有已注册的策略 Bean
     * @param strategyProperties 策略配置属性（crypto.strategy.configs）
     */
    public StrategyManager(List<Strategy> strategies, StrategyProperties strategyProperties) {
        Map<String, StrategyProperties.StrategyConfig> configs = strategyProperties.getConfigs();

        for (Strategy strategy : strategies) {
            String name = strategy.name();
            strategyMap.put(name, strategy);

            // 从配置读取初始状态，未配置则默认启用
            boolean initialEnabled = true;
            StrategyProperties.StrategyConfig config = configs.get(name);
            if (config != null) {
                initialEnabled = config.isEnabled();
            }
            enabledMap.put(name, initialEnabled);
            log.info("Strategy '{}' registered, enabled={}", name, initialEnabled);
        }
    }

    /**
     * 启用策略。
     *
     * @param name 策略名称
     * @return true 如果策略存在并被启用，false 如果策略不存在
     */
    public boolean enableStrategy(String name) {
        if (!strategyMap.containsKey(name)) {
            log.warn("Cannot enable unknown strategy: {}", name);
            return false;
        }
        enabledMap.put(name, true);
        log.info("Strategy '{}' enabled", name);
        return true;
    }

    /**
     * 禁用策略。
     *
     * @param name 策略名称
     * @return true 如果策略存在并被禁用，false 如果策略不存在
     */
    public boolean disableStrategy(String name) {
        if (!strategyMap.containsKey(name)) {
            log.warn("Cannot disable unknown strategy: {}", name);
            return false;
        }
        enabledMap.put(name, false);
        log.info("Strategy '{}' disabled", name);
        return true;
    }

    /**
     * 查询策略是否启用。
     *
     * @param name 策略名称
     * @return true 如果策略存在且启用，false 如果策略不存在或已禁用
     */
    public boolean isStrategyEnabled(String name) {
        return enabledMap.getOrDefault(name, false);
    }

    /**
     * 获取所有活跃（已启用）的策略。
     */
    public List<Strategy> getActiveStrategies() {
        return strategyMap.entrySet().stream()
                .filter(e -> enabledMap.getOrDefault(e.getKey(), false))
                .map(Map.Entry::getValue)
                .toList();
    }

    /**
     * 获取所有已注册的策略（不论启用/禁用状态）。
     */
    public List<Strategy> getAllStrategies() {
        return List.copyOf(strategyMap.values());
    }

    /**
     * 获取策略（按名称）。
     *
     * @param name 策略名称
     * @return 策略实例，如果不存在则为空
     */
    public Optional<Strategy> getStrategy(String name) {
        return Optional.ofNullable(strategyMap.get(name));
    }
}
