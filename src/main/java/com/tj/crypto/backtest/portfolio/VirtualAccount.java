package com.tj.crypto.backtest.portfolio;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.InstrumentId;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.common.domain.MarketType;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 虚拟账户（现货模拟）。
 * 管理虚拟资金、持仓和交易记录。
 *
 * 设计决策：
 * - 简单的多/空持仓模型（不支持同时多空）
 * - 开仓时冻结资金，平仓时释放
 * - 所有计算使用 BigDecimal
 * - 支持手续费模型（可选）
 */
@Slf4j
public class VirtualAccount implements TradingAccount {

    private static final int SCALE = 8;

    private BigDecimal balance;
    private final BigDecimal initialBalance;
    private final Map<InstrumentId, Position> positions = new HashMap<>();
    private final List<Trade> trades = new ArrayList<>();
    private final FeeModel feeModel;
    private BigDecimal totalFeesPaid = BigDecimal.ZERO;

    public VirtualAccount(BigDecimal initialBalance) {
        this(initialBalance, null);
    }

    public VirtualAccount(BigDecimal initialBalance, FeeModel feeModel) {
        this.initialBalance = initialBalance;
        this.balance = initialBalance;
        this.feeModel = feeModel;
    }

    /**
     * 开仓。
     *
     * @return true 如果开仓成功，false 如果余额不足
     */
    public boolean openPosition(Instrument instrument, OrderSide side,
                                BigDecimal quantity, BigDecimal price, long timestamp) {
        if (instrument.marketType() == MarketType.SPOT && side == OrderSide.SHORT) {
            log.warn("Spot account cannot open a short position: {}", instrument.id().value());
            return false;
        }
        if (quantity.compareTo(BigDecimal.ZERO) <= 0 || price.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Invalid position params: quantity={}, price={}", quantity, price);
            return false;
        }
        BigDecimal cost = quantity.multiply(price);

        // 计算开仓手续费
        BigDecimal openFee = BigDecimal.ZERO;
        if (feeModel != null) {
            openFee = feeModel.calculateFee(side, quantity, price);
        }

        BigDecimal totalCost = cost.add(openFee);
        if (balance.compareTo(totalCost) < 0) {
            log.warn("Insufficient balance: need {} (cost={} + fee={}), have {}",
                    totalCost, cost, openFee, balance);
            return false;
        }

        InstrumentId key = instrument.id();
        if (positions.containsKey(key)) {
            log.warn("Position already exists for {}", key);
            return false;
        }

        balance = balance.subtract(totalCost);
        totalFeesPaid = totalFeesPaid.add(openFee);
        positions.put(key, new Position(instrument, side, quantity, price, timestamp));
        log.debug("Opened {} {} {} @ ${}, fee=${}", side, quantity, instrument.symbol(), price, openFee);
        return true;
    }

    /**
     * 平仓。
     *
     * @return 交易记录，如果没有持仓返回 null
     */
    public Trade closePosition(Instrument instrument, BigDecimal price, long timestamp) {
        InstrumentId key = instrument.id();
        Position pos = positions.remove(key);
        if (pos == null) {
            return null;
        }

        BigDecimal pnl = pos.unrealizedPnL(price);
        BigDecimal originalCost = pos.quantity().multiply(pos.entryPrice());

        // 计算平仓手续费
        OrderSide closeSide = pos.side() == OrderSide.LONG ? OrderSide.SHORT : OrderSide.LONG;
        BigDecimal closeFee = BigDecimal.ZERO;
        if (feeModel != null) {
            closeFee = feeModel.calculateFee(closeSide, pos.quantity(), price);
        }

        // 退还原始成本 + 盈亏 - 平仓手续费
        balance = balance.add(originalCost).add(pnl).subtract(closeFee);
        totalFeesPaid = totalFeesPaid.add(closeFee);

        // 总手续费 = 开仓手续费 + 平仓手续费（开仓手续费已从余额扣除，这里记录在 Trade 中）
        BigDecimal openFee = BigDecimal.ZERO;
        if (feeModel != null) {
            openFee = feeModel.calculateFee(pos.side(), pos.quantity(), pos.entryPrice());
        }
        BigDecimal totalTradeFee = openFee.add(closeFee);

        Trade trade = new Trade(
                pos.instrument(),
                pos.side(),
                pos.quantity(),
                pos.entryPrice(),
                price,
                pos.entryTime(),
                timestamp,
                pnl,
                totalTradeFee
        );
        trades.add(trade);
        log.debug("Closed {} @ ${}, PnL: ${}, fee=${}", instrument.symbol(), price, pnl, closeFee);
        return trade;
    }

    /**
     * 获取未实现盈亏。
     */
    public BigDecimal getUnrealizedPnL(Map<String, BigDecimal> currentPrices) {
        BigDecimal total = BigDecimal.ZERO;
        for (Position position : positions.values()) {
            BigDecimal price = resolvePrice(currentPrices, position.instrument());
            if (price != null) {
                total = total.add(position.unrealizedPnL(price));
            }
        }
        return total;
    }

    @Override
    public BigDecimal getPositionValue(Instrument instrument, BigDecimal currentPrice) {
        Position pos = positions.get(instrument.id());
        if (pos == null) {
            return BigDecimal.ZERO;
        }
        return pos.quantity().multiply(currentPrice);
    }

    @Override
    public BigDecimal getTotalPositionValue(Map<String, BigDecimal> currentPrices) {
        return getPositionMarketValue(currentPrices);
    }

    /**
     * 获取持仓市值（所有持仓按当前价格计算的总价值）。
     */
    public BigDecimal getPositionMarketValue(Map<String, BigDecimal> currentPrices) {
        BigDecimal total = BigDecimal.ZERO;
        for (Position position : positions.values()) {
            BigDecimal price = resolvePrice(currentPrices, position.instrument());
            if (price != null) {
                total = total.add(position.quantity().multiply(price));
            }
        }
        return total;
    }

    /**
     * 获取总权益 = 余额 + 持仓市值。
     * 开仓时余额已扣除成本，所以权益 = 余额 + 持仓当前市值。
     */
    public BigDecimal getTotalEquity(Map<String, BigDecimal> currentPrices) {
        return balance.add(getPositionMarketValue(currentPrices));
    }

    @Override
    public AccountRiskSnapshot riskSnapshot(Instrument instrument, BigDecimal currentPrice) {
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal target = BigDecimal.ZERO;
        for (Position position : positions.values()) {
            BigDecimal mark = position.instrument().id().equals(instrument.id())
                    ? currentPrice : position.entryPrice();
            BigDecimal value = position.quantity().multiply(mark).abs();
            gross = gross.add(value);
            if (position.instrument().id().equals(instrument.id())) {
                target = value;
            }
        }
        return new AccountRiskSnapshot(balance.add(gross), balance, gross, target);
    }

    public BigDecimal getBalance() { return balance; }
    public BigDecimal getInitialBalance() { return initialBalance; }
    public Map<String, Position> getPositions() {
        Map<String, Position> view = new HashMap<>();
        positions.forEach((key, value) -> view.put(key.value(), value));
        return Map.copyOf(view);
    }
    public List<Trade> getTrades() { return List.copyOf(trades); }
    public boolean hasPosition(Instrument instrument) { return positions.containsKey(instrument.id()); }

    /**
     * 获取指定交易工具的当前持仓方向。
     *
     * @param instrument 交易工具
     * @return 持仓方向（LONG/SHORT），无持仓返回 null
     */
    public OrderSide getPositionSide(Instrument instrument) {
        Position pos = positions.get(instrument.id());
        return pos != null ? pos.side() : null;
    }

    @Override
    public BigDecimal getPositionQuantity(Instrument instrument) {
        Position pos = positions.get(instrument.id());
        return pos != null ? pos.quantity() : BigDecimal.ZERO;
    }

    public BigDecimal getTotalFeesPaid() { return totalFeesPaid; }

    private BigDecimal resolvePrice(Map<String, BigDecimal> prices, Instrument instrument) {
        BigDecimal canonical = prices.get(instrument.id().value());
        return canonical != null ? canonical : prices.get(instrument.symbol());
    }
}
