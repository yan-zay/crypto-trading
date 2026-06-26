package com.tj.crypto.strategy.core;

import com.tj.crypto.marketdata.model.MarketEvent;

import java.util.Set;

/**
 * 策略接口。
 * 所有策略实现此接口，由 StrategyEngine 统一管理和分发。
 *
 * 设计决策：
 * - 接口而非抽象类，策略不需要共享状态
 * - listenedEvents() 返回 Class 集合，引擎按类型精确匹配
 * - onEvent() 接收 StrategyContext，策略可查询因子但不直接依赖因子系统
 * - onTimer() 可选实现，用于定时检查（如轮询未处理数据）
 *
 * 生命周期：
 * - Spring Bean 创建 → StrategyEngine 注册 → onEvent() / onTimer() → Spring 容器关闭
 */
public interface Strategy {

    /**
     * 策略名称（唯一标识）。
     */
    String name();

    /**
     * 监听的事件类型集合。
     * 引擎只会将匹配类型的事件分发给此策略。
     */
    Set<Class<? extends MarketEvent>> listenedEvents();

    /**
     * 处理市场事件。
     *
     * @param event   市场事件
     * @param context 策略上下文（可查询因子）
     */
    void onEvent(MarketEvent event, StrategyContext context);

    /**
     * 定时触发（可选）。
     * 用于周期性检查、轮询等场景。
     *
     * @param timestamp 当前时间戳
     * @param context   策略上下文
     */
    default void onTimer(long timestamp, StrategyContext context) {}
}
