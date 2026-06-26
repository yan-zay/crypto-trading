package com.tj.crypto.backtest.portfolio;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.OrderSide;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 虚拟账户。
 * 管理虚拟资金、持仓和交易记录。
 *
 * 设计决策：
 * - 简单的多/空持仓模型（不支持同时多空）
 * - 开仓时冻结资金，平仓时释放
 * - 所有计算使用 BigDecimal
 */
@Slf4j
public class VirtualAccount {

    private BigDecimal balance;
    private final BigDecimal initialBalance;
    private final Map<String, Position> positions = new HashMap<>();
    private final List<Trade> trades = new ArrayList<>();

    public VirtualAccount(BigDecimal initialBalance) {
        this.initialBalance = initialBalance;
        this.balance = initialBalance;
    }

    /**
     * 开仓。
     *
     * @return true 如果开仓成功，false 如果余额不足
     */
    public boolean openPosition(Instrument instrument, OrderSide side,
                                BigDecimal quantity, BigDecimal price, long timestamp) {
        BigDecimal cost = quantity.multiply(price);
        if (balance.compareTo(cost) < 0) {
            log.warn("Insufficient balance: need {}, have {}", cost, balance);
            return false;
        }

        String key = instrument.symbol();
        if (positions.containsKey(key)) {
            log.warn("Position already exists for {}", key);
            return false;
        }

        balance = balance.subtract(cost);
        positions.put(key, new Position(instrument, side, quantity, price, timestamp));
        log.debug("Opened {} {} {} @ ${}", side, quantity, instrument.symbol(), price);
        return true;
    }

    /**
     * 平仓。
     *
     * @return 交易记录，如果没有持仓返回 null
     */
    public Trade closePosition(Instrument instrument, BigDecimal price, long timestamp) {
        String key = instrument.symbol();
        Position pos = positions.remove(key);
        if (pos == null) {
            return null;
        }

        BigDecimal pnl = pos.unrealizedPnL(price);
        BigDecimal originalCost = pos.quantity().multiply(pos.entryPrice());
        // 退还原始成本 + 盈亏（多头和空头统一处理）
        balance = balance.add(originalCost).add(pnl);

        Trade trade = new Trade(
                pos.instrument(),
                pos.side(),
                pos.quantity(),
                pos.entryPrice(),
                price,
                pos.entryTime(),
                timestamp,
                pnl
        );
        trades.add(trade);
        log.debug("Closed {} @ ${}, PnL: ${}", instrument.symbol(), price, pnl);
        return trade;
    }

    /**
     * 获取未实现盈亏。
     */
    public BigDecimal getUnrealizedPnL(Map<String, BigDecimal> currentPrices) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, Position> entry : positions.entrySet()) {
            BigDecimal price = currentPrices.get(entry.getKey());
            if (price != null) {
                total = total.add(entry.getValue().unrealizedPnL(price));
            }
        }
        return total;
    }

    /**
     * 获取总权益（余额 + 未实现盈亏）。
     */
    public BigDecimal getTotalEquity(Map<String, BigDecimal> currentPrices) {
        return balance.add(getUnrealizedPnL(currentPrices));
    }

    public BigDecimal getBalance() { return balance; }
    public BigDecimal getInitialBalance() { return initialBalance; }
    public Map<String, Position> getPositions() { return Map.copyOf(positions); }
    public List<Trade> getTrades() { return List.copyOf(trades); }
    public boolean hasPosition(Instrument instrument) { return positions.containsKey(instrument.symbol()); }
}
