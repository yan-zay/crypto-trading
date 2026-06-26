package com.tj.crypto.strategy.core;

import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.LiquidationEvent;
import com.tj.crypto.marketdata.model.MarketEvent;
import com.tj.crypto.strategy.config.StrategyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * StrategyManager 单元测试。
 * 验证策略启用/禁用、状态查询、配置初始化等功能。
 */
class StrategyManagerTest {

    private Strategy macdStrategy;
    private Strategy rsiStrategy;
    private StrategyProperties strategyProperties;

    @BeforeEach
    void setUp() {
        macdStrategy = createMockStrategy("MacdCross", BarEvent.class);
        rsiStrategy = createMockStrategy("RsiCross", BarEvent.class);

        strategyProperties = new StrategyProperties();
    }

    private Strategy createMockStrategy(String name, Class<? extends MarketEvent>... eventTypes) {
        Strategy strategy = mock(Strategy.class);
        when(strategy.name()).thenReturn(name);
        when(strategy.listenedEvents()).thenReturn(Set.of(eventTypes));
        return strategy;
    }

    @Test
    @DisplayName("未配置的策略默认启用")
    void shouldDefaultToEnabledWhenNotConfigured() {
        StrategyManager manager = new StrategyManager(
                List.of(macdStrategy, rsiStrategy), strategyProperties);

        assertThat(manager.isStrategyEnabled("MacdCross")).isTrue();
        assertThat(manager.isStrategyEnabled("RsiCross")).isTrue();
    }

    @Test
    @DisplayName("配置为禁用的策略初始状态为禁用")
    void shouldInitializeDisabledFromConfig() {
        StrategyProperties.StrategyConfig macdConfig = new StrategyProperties.StrategyConfig();
        macdConfig.setEnabled(false);
        strategyProperties.setConfigs(Map.of("MacdCross", macdConfig));

        StrategyManager manager = new StrategyManager(
                List.of(macdStrategy, rsiStrategy), strategyProperties);

        assertThat(manager.isStrategyEnabled("MacdCross")).isFalse();
        assertThat(manager.isStrategyEnabled("RsiCross")).isTrue();
    }

    @Test
    @DisplayName("启用策略返回 true")
    void shouldEnableStrategy() {
        StrategyProperties.StrategyConfig macdConfig = new StrategyProperties.StrategyConfig();
        macdConfig.setEnabled(false);
        strategyProperties.setConfigs(Map.of("MacdCross", macdConfig));

        StrategyManager manager = new StrategyManager(
                List.of(macdStrategy), strategyProperties);

        assertThat(manager.isStrategyEnabled("MacdCross")).isFalse();
        boolean result = manager.enableStrategy("MacdCross");

        assertThat(result).isTrue();
        assertThat(manager.isStrategyEnabled("MacdCross")).isTrue();
    }

    @Test
    @DisplayName("禁用策略返回 true")
    void shouldDisableStrategy() {
        StrategyManager manager = new StrategyManager(
                List.of(macdStrategy), strategyProperties);

        assertThat(manager.isStrategyEnabled("MacdCross")).isTrue();
        boolean result = manager.disableStrategy("MacdCross");

        assertThat(result).isTrue();
        assertThat(manager.isStrategyEnabled("MacdCross")).isFalse();
    }

    @Test
    @DisplayName("启用不存在的策略返回 false")
    void shouldReturnFalseWhenEnablingUnknownStrategy() {
        StrategyManager manager = new StrategyManager(
                List.of(macdStrategy), strategyProperties);

        boolean result = manager.enableStrategy("Unknown");

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("禁用不存在的策略返回 false")
    void shouldReturnFalseWhenDisablingUnknownStrategy() {
        StrategyManager manager = new StrategyManager(
                List.of(macdStrategy), strategyProperties);

        boolean result = manager.disableStrategy("Unknown");

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("查询不存在的策略状态返回 false")
    void shouldReturnFalseForUnknownStrategyEnabled() {
        StrategyManager manager = new StrategyManager(
                List.of(macdStrategy), strategyProperties);

        assertThat(manager.isStrategyEnabled("Unknown")).isFalse();
    }

    @Test
    @DisplayName("getActiveStrategies 只返回启用的策略")
    void shouldReturnOnlyActiveStrategies() {
        StrategyManager manager = new StrategyManager(
                List.of(macdStrategy, rsiStrategy), strategyProperties);

        // 两个策略都启用
        assertThat(manager.getActiveStrategies()).hasSize(2);

        // 禁用一个
        manager.disableStrategy("MacdCross");
        assertThat(manager.getActiveStrategies()).hasSize(1);
        assertThat(manager.getActiveStrategies().get(0).name()).isEqualTo("RsiCross");
    }

    @Test
    @DisplayName("getAllStrategies 返回所有策略不论状态")
    void shouldReturnAllStrategiesRegardlessOfState() {
        StrategyManager manager = new StrategyManager(
                List.of(macdStrategy, rsiStrategy), strategyProperties);

        // 禁用一个
        manager.disableStrategy("MacdCross");

        assertThat(manager.getAllStrategies()).hasSize(2);
    }

    @Test
    @DisplayName("getStrategy 按名称查找策略")
    void shouldFindStrategyByName() {
        StrategyManager manager = new StrategyManager(
                List.of(macdStrategy, rsiStrategy), strategyProperties);

        Optional<Strategy> found = manager.getStrategy("MacdCross");
        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("MacdCross");
    }

    @Test
    @DisplayName("getStrategy 不存在时返回空 Optional")
    void shouldReturnEmptyForUnknownStrategy() {
        StrategyManager manager = new StrategyManager(
                List.of(macdStrategy), strategyProperties);

        Optional<Strategy> found = manager.getStrategy("Unknown");
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("禁用后重新启用策略应恢复工作")
    void shouldReEnableDisabledStrategy() {
        StrategyManager manager = new StrategyManager(
                List.of(macdStrategy), strategyProperties);

        manager.disableStrategy("MacdCross");
        assertThat(manager.isStrategyEnabled("MacdCross")).isFalse();
        assertThat(manager.getActiveStrategies()).isEmpty();

        manager.enableStrategy("MacdCross");
        assertThat(manager.isStrategyEnabled("MacdCross")).isTrue();
        assertThat(manager.getActiveStrategies()).hasSize(1);
    }

    @Test
    @DisplayName("空策略列表应正常初始化")
    void shouldHandleEmptyStrategyList() {
        StrategyManager manager = new StrategyManager(
                List.of(), strategyProperties);

        assertThat(manager.getAllStrategies()).isEmpty();
        assertThat(manager.getActiveStrategies()).isEmpty();
        assertThat(manager.enableStrategy("Unknown")).isFalse();
        assertThat(manager.disableStrategy("Unknown")).isFalse();
    }
}
