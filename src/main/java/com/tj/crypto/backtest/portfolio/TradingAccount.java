package com.tj.crypto.backtest.portfolio;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.OrderSide;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 交易账户统一接口。
 * VirtualAccount（现货模拟）和 FuturesAccount（合约模拟）共同实现。
 *
 * <p>ExecutionEngine、RiskEngine、PositionSizer 通过此接口操作账户，
 * 实现现货/合约的可插拔切换。
 */
public interface TradingAccount {

    /**
     * 开仓。
     *
     * @param instrument 交易工具
     * @param side       多/空方向
     * @param quantity   数量
     * @param price      价格
     * @param timestamp  时间戳
     * @return true 如果开仓成功
     */
    boolean openPosition(Instrument instrument, OrderSide side,
                         BigDecimal quantity, BigDecimal price, long timestamp);

    /**
     * 平仓。
     *
     * @param instrument 交易工具
     * @param price      平仓价格
     * @param timestamp  时间戳
     * @return 交易记录，如果没有持仓返回 null
     */
    Trade closePosition(Instrument instrument, BigDecimal price, long timestamp);

    /**
     * 是否持有指定交易工具的仓位。
     */
    boolean hasPosition(Instrument instrument);

    /**
     * 获取指定交易工具的持仓方向。
     *
     * @return 持仓方向（LONG/SHORT），无持仓返回 null
     */
    OrderSide getPositionSide(Instrument instrument);

    /**
     * 获取指定交易工具的持仓数量。
     *
     * @return 持仓数量，无持仓返回 ZERO
     */
    BigDecimal getPositionQuantity(Instrument instrument);

    /**
     * 获取当前余额。
     */
    BigDecimal getBalance();

    /**
     * 获取初始余额。
     */
    BigDecimal getInitialBalance();

    /**
     * 获取所有持仓（只读视图）。
     */
    Map<String, ?> getPositions();

    /**
     * 获取所有已平仓交易记录（只读视图）。
     */
    List<Trade> getTrades();

    /**
     * 获取已支付的总手续费。
     */
    BigDecimal getTotalFeesPaid();

    /**
     * 获取指定交易工具的持仓市值。
     *
     * @param instrument 交易工具
     * @param currentPrice 当前价格
     * @return 持仓市值，无持仓返回 ZERO
     */
    BigDecimal getPositionValue(Instrument instrument, BigDecimal currentPrice);

    /**
     * 获取所有持仓的总市值。
     *
     * @param currentPrices 当前价格映射（symbol -> price）
     * @return 所有持仓市值之和
     */
    BigDecimal getTotalPositionValue(Map<String, BigDecimal> currentPrices);

    /**
     * 获取未实现盈亏。
     *
     * @param currentPrices 当前价格映射（symbol -> price）
     */
    BigDecimal getUnrealizedPnL(Map<String, BigDecimal> currentPrices);

    /**
     * 获取总权益 = 余额 + 未实现盈亏。
     */
    BigDecimal getTotalEquity(Map<String, BigDecimal> currentPrices);

    /**
     * 以待执行订单价格重估目标资产，其余资产使用账户内可用的最近估值，
     * 返回同一时点的权益和敞口，供纯函数风控使用。
     */
    AccountRiskSnapshot riskSnapshot(Instrument instrument, BigDecimal currentPrice);
}
