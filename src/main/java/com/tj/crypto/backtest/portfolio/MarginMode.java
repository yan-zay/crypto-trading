package com.tj.crypto.backtest.portfolio;

/**
 * 保证金模式枚举。
 *
 * ISOLATED — 逐仓保证金：每个仓位独立保证金，强平不影响账户余额。
 * CROSSED  — 全仓保证金：账户全部可用余额作为所有仓位的保证金。
 */
public enum MarginMode {
    ISOLATED,
    CROSSED
}
