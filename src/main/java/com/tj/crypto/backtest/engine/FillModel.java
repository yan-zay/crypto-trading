package com.tj.crypto.backtest.engine;

import com.tj.crypto.marketdata.model.BarEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 成交模型接口。
 * 决定订单的基础成交价格（不含滑点），防止未来函数。
 *
 * <p>两种模式：
 * <ul>
 *   <li>CLOSE_ONLY: 使用收盘价（最保守，防止未来函数）</li>
 *   <li>INTERPOLATED: 使用 (open + close) / 2（更乐观，标注为假设）</li>
 * </ul>
 *
 * <p>注意：此模型仅计算基础价格，滑点由 ExecutionEngine 单独处理。
 */
public interface FillModel {

    /**
     * 计算基础成交价格（不含滑点）。
     *
     * @param bar 当前 K 线事件
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
     * 仅在 K 线收盘价成交。
     * 最保守的假设，防止未来函数。
     */
    class CloseOnlyFillModel implements FillModel {

        @Override
        public BigDecimal calculateBasePrice(BarEvent bar) {
            return bar.close();
        }

        @Override
        public BacktestAssumptions.FillMode fillMode() {
            return BacktestAssumptions.FillMode.CLOSE_ONLY;
        }
    }

    /**
     * 使用 (open + close) / 2 成交。
     * 更乐观的假设，标注为乐观。
     */
    class InterpolatedFillModel implements FillModel {

        private static final int SCALE = 8;

        @Override
        public BigDecimal calculateBasePrice(BarEvent bar) {
            return bar.open().add(bar.close())
                    .divide(BigDecimal.valueOf(2), SCALE, RoundingMode.HALF_UP);
        }

        @Override
        public BacktestAssumptions.FillMode fillMode() {
            return BacktestAssumptions.FillMode.INTERPOLATED;
        }
    }
}
