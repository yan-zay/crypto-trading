package com.tj.crypto.factor.analysis;

import java.math.BigDecimal;

/**
 * 因子收益统计，不可变值对象。
 * 表示因子在正/负值区间的平均收益及统计显著性。
 *
 * @param factorName             因子名称
 * @param avgReturnWhenPositive  因子为正时的平均收益
 * @param avgReturnWhenNegative  因子为负时的平均收益
 * @param tStat                  t 统计量（正负组收益差异的显著性）
 * @param pValue                 p 值（双侧检验）
 */
public record FactorReturnStats(
        String factorName,
        BigDecimal avgReturnWhenPositive,
        BigDecimal avgReturnWhenNegative,
        double tStat,
        double pValue
) {}
