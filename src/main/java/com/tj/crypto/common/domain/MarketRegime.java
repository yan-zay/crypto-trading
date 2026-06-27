package com.tj.crypto.common.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 市场状态枚举。
 * 用于对市场环境进行分类，支持策略按市场状态评估和因子分析。
 */
@Getter
@AllArgsConstructor
public enum MarketRegime {

    TRENDING_UP("trending_up", "上升趋势"),
    TRENDING_DOWN("trending_down", "下降趋势"),
    RANGING("ranging", "震荡盘整"),
    HIGH_VOLATILITY("high_volatility", "高波动"),
    LOW_VOLATILITY("low_volatility", "低波动"),
    ;

    private final String code;
    private final String displayName;
}
