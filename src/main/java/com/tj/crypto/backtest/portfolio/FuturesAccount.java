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
 * 永续合约虚拟账户。
 * 支持多空持仓、保证金、杠杆、冻结资金、可用余额、总权益。
 *
 * 余额语义（标准期货账户模型）：
 * - balance：结算余额，包含已冻结保证金。开仓时不扣减保证金，仅扣减手续费。
 * - availableBalance：可用余额 = balance - totalMargin。
 * - totalEquity：总权益 = balance + 未实现盈亏。
 * - 平仓时：balance += realizedPnL - closeFee，保证金自动释放。
 * - 强平时：balance -= 保证金亏损（超出保证金部分截断为 0）。
 *
 * 设计决策：
 * - 逐仓模式：每个仓位独立保证金，强平不影响其他仓位。
 * - 全仓模式：预留枚举，具体逻辑暂未实现。
 * - 所有计算使用 BigDecimal，scale = 8。
 * - 支持手续费模型（可选）。
 * - 支持资金费率结算。
 */
@Slf4j
public class FuturesAccount {

    private static final int SCALE = 8;

    private BigDecimal balance;
    private final BigDecimal initialBalance;
    private final Map<String, FuturesPosition> positions = new HashMap<>();
    private final List<Trade> trades = new ArrayList<>();
    private final FeeModel feeModel;
    private BigDecimal totalFeesPaid = BigDecimal.ZERO;
    private BigDecimal totalFundingPaid = BigDecimal.ZERO;

    public FuturesAccount(BigDecimal initialBalance) {
        this(initialBalance, null);
    }

    public FuturesAccount(BigDecimal initialBalance, FeeModel feeModel) {
        this.initialBalance = initialBalance;
        this.balance = initialBalance;
        this.feeModel = feeModel;
    }

    /**
     * 开仓。
     * 保证金从可用余额中冻结（通过 getAvailableBalance 反映），但不从 balance 中扣减。
     * 仅扣减手续费。
     *
     * @param instrument 交易工具
     * @param side       多/空方向
     * @param quantity   持仓数量
     * @param price      开仓价格
     * @param leverage   杠杆倍数（>= 1）
     * @param marginMode 保证金模式
     * @return true 如果开仓成功
     * @throws IllegalArgumentException 如果 leverage < 1
     */
    public boolean openPosition(Instrument instrument, OrderSide side,
                                BigDecimal quantity, BigDecimal price,
                                int leverage, MarginMode marginMode) {
        if (leverage < 1) {
            throw new IllegalArgumentException("Leverage must be >= 1, got: " + leverage);
        }

        String key = instrument.symbol();
        if (positions.containsKey(key)) {
            log.warn("Position already exists for {}", key);
            return false;
        }

        BigDecimal positionValue = quantity.multiply(price);
        BigDecimal margin = positionValue.divide(BigDecimal.valueOf(leverage), SCALE, RoundingMode.HALF_UP);

        // 计算开仓手续费
        BigDecimal openFee = BigDecimal.ZERO;
        if (feeModel != null) {
            openFee = feeModel.calculateFee(side, quantity, price);
        }

        // 检查可用余额是否足够（当前可用 = balance - 已有保证金）
        BigDecimal currentAvailable = calculateAvailableBalance();
        BigDecimal totalCost = margin.add(openFee);
        if (currentAvailable.compareTo(totalCost) < 0) {
            log.warn("Insufficient available balance: need {} (margin={} + fee={}), have {}",
                    totalCost, margin, openFee, currentAvailable);
            return false;
        }

        // 仅扣减手续费，保证金通过 availableBalance 冻结
        balance = balance.subtract(openFee);
        totalFeesPaid = totalFeesPaid.add(openFee);

        FuturesPosition position = buildPosition(instrument, side, quantity, price, leverage, marginMode, margin);
        positions.put(key, position);
        log.debug("Opened {} {} {}x {} @ ${}, margin={}, fee={}",
                side, instrument.symbol(), leverage, quantity, price, margin, openFee);
        return true;
    }

    /**
     * 平仓。
     * 保证金自动释放，盈亏结算到 balance。
     *
     * @param instrument 交易工具
     * @param price      平仓价格
     * @return 交易记录，如果没有持仓返回 null
     */
    public Trade closePosition(Instrument instrument, BigDecimal price) {
        String key = instrument.symbol();
        FuturesPosition pos = positions.remove(key);
        if (pos == null) {
            return null;
        }

        return settlePosition(pos, price);
    }

    /**
     * 强制平仓（爆仓）。
     * 保证金亏损部分扣除，仅退还保证金剩余（如有）。
     *
     * @param instrument 交易工具
     * @param price      强平价格
     * @return 交易记录
     */
    public Trade liquidatePosition(Instrument instrument, BigDecimal price) {
        String key = instrument.symbol();
        FuturesPosition pos = positions.remove(key);
        if (pos == null) {
            return null;
        }

        BigDecimal pnl = pos.calculateUnrealizedPnL(price);
        // 保证金剩余 = 保证金 + 盈亏（负数表示亏损，截断到 0）
        BigDecimal marginRemaining = pos.margin().add(pnl).max(BigDecimal.ZERO);

        // 平仓手续费（强平也收手续费）
        OrderSide closeSide = pos.side() == OrderSide.LONG ? OrderSide.SHORT : OrderSide.LONG;
        BigDecimal closeFee = BigDecimal.ZERO;
        if (feeModel != null) {
            closeFee = feeModel.calculateFee(closeSide, pos.quantity(), price);
        }

        // balance 调整：扣减保证金亏损，退还剩余，扣减手续费
        // 保证金亏损 = margin - marginRemaining
        BigDecimal marginLoss = pos.margin().subtract(marginRemaining);
        balance = balance.subtract(marginLoss).subtract(closeFee);
        totalFeesPaid = totalFeesPaid.add(closeFee);

        Trade trade = new Trade(
                pos.instrument(),
                pos.side(),
                pos.quantity(),
                pos.entryPrice(),
                price,
                0L,
                0L,
                pnl,
                closeFee
        );
        trades.add(trade);
        log.warn("Liquidated {} @ ${}, PnL: ${}, margin lost: ${}",
                instrument.symbol(), price, pnl, marginLoss);
        return trade;
    }

    /**
     * 结算资金费率。
     * 正费率：多头付给空头。负费率：空头付给多头。
     *
     * @param instrument   交易工具
     * @param fundingRate  资金费率（如 0.0001 表示 0.01%）
     */
    public void applyFundingRate(Instrument instrument, BigDecimal fundingRate) {
        String key = instrument.symbol();
        FuturesPosition pos = positions.get(key);
        if (pos == null) {
            return;
        }

        BigDecimal positionValue = pos.quantity().multiply(pos.entryPrice());
        BigDecimal fundingAmount = positionValue.multiply(fundingRate)
                .setScale(SCALE, RoundingMode.HALF_UP);

        BigDecimal adjustment;
        if (pos.side() == OrderSide.LONG) {
            // 多头：正费率付费（扣减），负费率收费（增加）
            adjustment = fundingAmount.negate();
        } else {
            // 空头：正费率收费（增加），负费率付费（扣减）
            adjustment = fundingAmount;
        }

        balance = balance.add(adjustment);
        if (adjustment.compareTo(BigDecimal.ZERO) < 0) {
            totalFundingPaid = totalFundingPaid.add(adjustment.abs());
        }
        log.debug("Funding settlement for {}: rate={}, amount={}, adjustment={}",
                instrument.symbol(), fundingRate, fundingAmount, adjustment);
    }

    /**
     * 获取所有持仓的未实现盈亏总和。
     */
    public BigDecimal getUnrealizedPnL(Map<String, BigDecimal> currentPrices) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, FuturesPosition> entry : positions.entrySet()) {
            BigDecimal price = currentPrices.get(entry.getKey());
            if (price != null) {
                total = total.add(entry.getValue().calculateUnrealizedPnL(price));
            }
        }
        return total.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 获取总权益 = 余额 + 未实现盈亏。
     * balance 已包含已冻结保证金，无需再加 margin。
     */
    public BigDecimal getTotalEquity(Map<String, BigDecimal> currentPrices) {
        return balance.add(getUnrealizedPnL(currentPrices))
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 获取保证金比率 = 总维持保证金 / 总权益。
     * 比率 >= 1 表示应触发强平。
     *
     * @param currentPrices 当前价格
     * @return 保证金比率
     */
    public BigDecimal getMarginRatio(Map<String, BigDecimal> currentPrices) {
        BigDecimal totalEquity = getTotalEquity(currentPrices);
        if (totalEquity.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ONE; // 已经资不抵债
        }

        BigDecimal totalMaintenanceMargin = BigDecimal.ZERO;
        for (FuturesPosition pos : positions.values()) {
            BigDecimal positionValue = pos.quantity().multiply(pos.entryPrice());
            totalMaintenanceMargin = totalMaintenanceMargin.add(
                    positionValue.multiply(new BigDecimal("0.005"))
                            .setScale(SCALE, RoundingMode.HALF_UP));
        }

        return totalMaintenanceMargin.divide(totalEquity, SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 获取指定仓位的强平价格。
     *
     * @param instrument 交易工具
     * @return 强平价格，无持仓返回 null
     */
    public BigDecimal getLiquidationPrice(Instrument instrument) {
        FuturesPosition pos = positions.get(instrument.symbol());
        return pos != null ? pos.liquidationPrice() : null;
    }

    /**
     * 获取可用余额 = 结算余额 - 已冻结保证金。
     */
    public BigDecimal getAvailableBalance() {
        return calculateAvailableBalance();
    }

    /**
     * 获取已冻结保证金总和。
     */
    public BigDecimal getTotalMargin() {
        BigDecimal total = BigDecimal.ZERO;
        for (FuturesPosition pos : positions.values()) {
            total = total.add(pos.margin());
        }
        return total.setScale(SCALE, RoundingMode.HALF_UP);
    }

    // ---- 内部方法 ----

    private BigDecimal calculateAvailableBalance() {
        BigDecimal totalMargin = BigDecimal.ZERO;
        for (FuturesPosition pos : positions.values()) {
            totalMargin = totalMargin.add(pos.margin());
        }
        return balance.subtract(totalMargin).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private FuturesPosition buildPosition(Instrument instrument, OrderSide side,
                                          BigDecimal quantity, BigDecimal price,
                                          int leverage, MarginMode marginMode, BigDecimal margin) {
        BigDecimal positionValue = quantity.multiply(price);
        BigDecimal maintenanceMargin = positionValue.multiply(new BigDecimal("0.005"))
                .setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal marginBuffer = margin.subtract(maintenanceMargin);
        BigDecimal priceBuffer = marginBuffer.divide(quantity, SCALE, RoundingMode.HALF_UP);

        BigDecimal liquidationPrice;
        if (side == OrderSide.LONG) {
            liquidationPrice = price.subtract(priceBuffer);
        } else {
            liquidationPrice = price.add(priceBuffer);
        }

        return new FuturesPosition(
                instrument, side, quantity, price,
                leverage, marginMode, margin,
                BigDecimal.ZERO, liquidationPrice
        );
    }

    private Trade settlePosition(FuturesPosition pos, BigDecimal price) {
        BigDecimal pnl = pos.calculateUnrealizedPnL(price);

        // 计算平仓手续费
        OrderSide closeSide = pos.side() == OrderSide.LONG ? OrderSide.SHORT : OrderSide.LONG;
        BigDecimal closeFee = BigDecimal.ZERO;
        if (feeModel != null) {
            closeFee = feeModel.calculateFee(closeSide, pos.quantity(), price);
        }

        // 盈亏结算到余额，手续费扣减，保证金自动释放（从 positions 移除即释放）
        balance = balance.add(pnl).subtract(closeFee);
        totalFeesPaid = totalFeesPaid.add(closeFee);

        Trade trade = new Trade(
                pos.instrument(),
                pos.side(),
                pos.quantity(),
                pos.entryPrice(),
                price,
                0L,
                0L,
                pnl,
                closeFee
        );
        trades.add(trade);
        log.debug("Closed {} @ ${}, PnL: ${}, fee={}",
                pos.instrument().symbol(), price, pnl, closeFee);
        return trade;
    }

    // ---- Getters ----

    public BigDecimal getBalance() { return balance; }
    public BigDecimal getInitialBalance() { return initialBalance; }
    public Map<String, FuturesPosition> getPositions() { return Map.copyOf(positions); }
    public List<Trade> getTrades() { return List.copyOf(trades); }
    public boolean hasPosition(Instrument instrument) { return positions.containsKey(instrument.symbol()); }
    public BigDecimal getTotalFeesPaid() { return totalFeesPaid; }
    public BigDecimal getTotalFundingPaid() { return totalFundingPaid; }
}
