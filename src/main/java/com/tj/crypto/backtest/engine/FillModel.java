package com.tj.crypto.backtest.engine;

import com.tj.crypto.marketdata.model.BarEvent;

import java.math.BigDecimal;

/**
 * 成交模型接口。
 * 决定订单的基础成交价格（不含滑点），防止未来函数。
 *
 * <p>两种模式：
 * <ul>
 *   <li>CLOSE_ONLY: 在信号 K 线收盘后，于下一根 K 线开盘成交</li>
 *   <li>INTERPOLATED: 在下一根 K 线使用 OHLC4 近似成交，仅用于敏感性分析</li>
 * </ul>
 *
 * <p>注意：此模型仅计算基础价格，滑点由 ExecutionEngine 单独处理。
 */
public interface FillModel {

    /**
     * 计算基础成交价格（不含滑点）。
     *
     * @param bar 信号产生后的下一根 K 线
     * @return 基础成交价格
     */
    BigDecimal calculateBasePrice(BarEvent bar);

    /**
     * 获取成交模式。
     *
     * @return 成交模式
     */
    BacktestAssumptions.FillMode fillMode();

    /**
     * 创建成交模型。
     *
     * @param fillMode 成交模式
     * @return 对应的成交模型
     */
    static FillModel create(BacktestAssumptions.FillMode fillMode) {
        return switch (fillMode) {
            case CLOSE_ONLY -> new CloseOnlyFillModel();
            case INTERPOLATED -> new InterpolatedFillModel();
        };
    }

    /**
     * 信号在上一根 K 线收盘后生成，本模型使用下一根 K 线开盘价。
     */
    class CloseOnlyFillModel implements FillModel {

        @Override
        public BigDecimal calculateBasePrice(BarEvent bar) {
            return bar.open();
        }

        @Override
        public BacktestAssumptions.FillMode fillMode() {
            return BacktestAssumptions.FillMode.CLOSE_ONLY;
        }
    }

    /**
     * 使用下一根 K 线 OHLC4 近似成交，只适合执行敏感性分析。
     */
    class InterpolatedFillModel implements FillModel {

        @Override
        public BigDecimal calculateBasePrice(BarEvent bar) {
            return bar.open().add(bar.high()).add(bar.low()).add(bar.close())
                    .divide(BigDecimal.valueOf(4), 8, java.math.RoundingMode.HALF_UP);
        }

        @Override
        public BacktestAssumptions.FillMode fillMode() {
            return BacktestAssumptions.FillMode.INTERPOLATED;
        }
    }
}
