package com.tj.crypto.strategy.core;

import java.util.List;

/**
 * 信号收集器接口。
 * 收集策略输出的 SignalEvent，供回测/模拟交易/监控使用。
 */
public interface SignalCollector {

    /**
     * 收集信号。
     */
    void collect(SignalEvent signal);

    /**
     * 获取某策略的所有信号。
     */
    List<SignalEvent> getSignals(String strategyName);

    /**
     * 获取某策略在时间范围内的信号。
     */
    List<SignalEvent> getSignals(String strategyName, long from, long to);

    /**
     * 获取所有信号。
     */
    List<SignalEvent> getAllSignals();

    /**
     * 清空所有信号（回测前重置）。
     */
    void clear();
}
