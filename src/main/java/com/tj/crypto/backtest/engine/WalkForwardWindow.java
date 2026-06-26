package com.tj.crypto.backtest.engine;

/**
 * Walk-forward 单个窗口的详细结果，不可变值对象。
 *
 * @param windowIndex    窗口索引（从 0 开始）
 * @param trainStartTime 训练期起始时间（毫秒）
 * @param trainEndTime   训练期结束时间（毫秒）
 * @param testStartTime  测试期起始时间（毫秒）
 * @param testEndTime    测试期结束时间（毫秒）
 * @param bestParams     训练期选出的最优参数
 * @param testResult     使用最优参数在测试期运行的回测结果
 */
public record WalkForwardWindow(
        int windowIndex,
        long trainStartTime,
        long trainEndTime,
        long testStartTime,
        long testEndTime,
        OptimizationResult bestParams,
        BacktestResult testResult
) {}
