package com.tj.crypto.execution;

import com.tj.crypto.backtest.portfolio.VirtualAccount;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderRejectReason;
import com.tj.crypto.execution.model.OrderType;
import com.tj.crypto.risk.RiskCheckResult;
import com.tj.crypto.risk.RiskEngine;
import com.tj.crypto.risk.PositionSizer;
import com.tj.crypto.strategy.core.SignalEvent;
import com.tj.crypto.strategy.core.SignalType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 执行引擎。
 * 信号 → 风控 → 仓位计算 → 滑点 → 下单。
 *
 * 流程：
 * 1. 创建订单意图
 * 2. RiskEngine.checkAll() 风控检查
 * 3. PositionSizer.calculateSize() 仓位计算
 * 4. SlippageModel.applySlippage() 滑点模拟
 * 5. VirtualAccount.openPosition/closePosition 执行
 */
@Slf4j
@Component
public class ExecutionEngine {

    private final RiskEngine riskEngine;
    private final PositionSizer positionSizer;
    private final SlippageModel slippageModel;

    public ExecutionEngine(RiskEngine riskEngine, PositionSizer positionSizer, SlippageModel slippageModel) {
        this.riskEngine = riskEngine;
        this.positionSizer = positionSizer;
        this.slippageModel = slippageModel;
    }

    /**
     * 执行交易信号。
     *
     * @param signal        交易信号
     * @param account       虚拟账户
     * @param currentPrice  当前价格
     * @param timestamp     时间戳
     * @return 执行后的订单（可能被拒绝）
     */
    public Order execute(SignalEvent signal, VirtualAccount account, BigDecimal currentPrice, long timestamp) {
        // 1. 信号类型转换为订单方向
        if (signal.type() == SignalType.HOLD) {
            return null; // HOLD 不产生订单
        }

        OrderSide side = signal.type() == SignalType.BUY ? OrderSide.LONG : OrderSide.SHORT;

        // 2. 计算仓位
        BigDecimal quantity = positionSizer.calculateSize(signal, account, currentPrice);
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return Order.rejected(signal.instrument(), side, OrderType.MARKET, BigDecimal.ZERO,
                    OrderRejectReason.INSUFFICIENT_BALANCE, timestamp);
        }

        // 3. 创建订单意图
        Order order = Order.create(signal.instrument(), side, OrderType.MARKET,
                quantity, currentPrice, timestamp);

        // 4. 风控检查
        RiskCheckResult riskResult = riskEngine.checkAll(order, account);
        if (!riskResult.isPassed()) {
            return Order.rejected(signal.instrument(), side, OrderType.MARKET, quantity,
                    riskResult.rejectReason(), timestamp);
        }

        // 5. 应用滑点
        BigDecimal executionPrice = slippageModel.applySlippage(currentPrice, side, OrderType.MARKET);

        // 6. 执行交易
        boolean success;
        if (side == OrderSide.LONG) {
            success = account.openPosition(signal.instrument(), side, quantity, executionPrice, timestamp);
        } else {
            success = account.closePosition(signal.instrument(), executionPrice, timestamp) != null;
        }

        if (!success) {
            return Order.rejected(signal.instrument(), side, OrderType.MARKET, quantity,
                    OrderRejectReason.INSUFFICIENT_BALANCE, timestamp);
        }

        log.info("[EXEC] {} {} {} @ ${} (slippage: {} → {})",
                side, quantity, signal.instrument().symbol(), executionPrice,
                currentPrice, executionPrice);

        return order.filled(timestamp);
    }
}
