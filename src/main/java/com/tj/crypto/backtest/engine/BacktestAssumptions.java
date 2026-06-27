package com.tj.crypto.backtest.engine;

import java.math.BigDecimal;

/**
 * 回测假设，不可变值对象。
 * 记录回测中的所有简化假设，用于评估回测结果的可信度。
 *
 * <p>关键假设：
 * <ul>
 *   <li>fillMode: 成交模式。CLOSE_ONLY 防止未来函数，INTERPOLATED 更乐观但不现实</li>
 *   <li>slippageModel: 滑点模型标识</li>
 *   <li>feeModel: 手续费模型标识</li>
 *   <li>minNotional: 最小名义价值（低于此值的订单不执行）</li>
 *   <li>quantityPrecision: 数量精度（小数位数）</li>
 *   <li>pricePrecision: 价格精度（小数位数）</li>
 *   <li>fundingRateEnabled: 是否模拟资金费率</li>
 * </ul>
 *
 * @param fillMode           成交模式
 * @param slippageModel      滑点模型标识
 * @param feeModel           手续费模型标识
 * @param minNotional        最小名义价值
 * @param quantityPrecision  数量精度（小数位数）
 * @param pricePrecision     价格精度（小数位数）
 * @param fundingRateEnabled 是否模拟资金费率
 */
public record BacktestAssumptions(
        FillMode fillMode,
        String slippageModel,
        String feeModel,
        BigDecimal minNotional,
        int quantityPrecision,
        int pricePrecision,
        boolean fundingRateEnabled
) {

    /**
     * 紧凑构造函数，确保默认值。
     */
    public BacktestAssumptions {
        if (fillMode == null) {
            fillMode = FillMode.CLOSE_ONLY;
        }
        if (slippageModel == null) {
            slippageModel = "FIXED";
        }
        if (feeModel == null) {
            feeModel = "MAKER_TAKER";
        }
        if (minNotional == null) {
            minNotional = BigDecimal.ONE;
        }
    }

    /**
     * 创建默认假设（最保守的配置）。
     *
     * @return 默认假设
     */
    public static BacktestAssumptions defaults() {
        return new BacktestAssumptions(
                FillMode.CLOSE_ONLY,
                "FIXED",
                "MAKER_TAKER",
                BigDecimal.ONE,
                3,
                2,
                false
        );
    }

    /**
     * 创建乐观假设（更宽松的配置，标注为乐观）。
     *
     * @return 乐观假设
     */
    public static BacktestAssumptions optimistic() {
        return new BacktestAssumptions(
                FillMode.INTERPOLATED,
                "FIXED",
                "MAKER_TAKER",
                BigDecimal.ONE,
                3,
                2,
                false
        );
    }

    /**
     * 成交模式枚举。
     */
    public enum FillMode {
        /**
         * 仅在 K 线收盘价成交。
         * 防止未来函数，是最保守的假设。
         */
        CLOSE_ONLY,

        /**
         * 使用 (open + close) / 2 成交。
         * 更乐观的假设，标注为乐观。
         */
        INTERPOLATED
    }
}
