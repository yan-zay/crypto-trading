package com.tj.crypto.trading.paper;

import com.tj.crypto.storage.entity.OmsFillDO;
import com.tj.crypto.storage.entity.OmsOrderDO;
import com.tj.crypto.storage.mapper.OmsFillMapper;
import com.tj.crypto.storage.mapper.OmsOrderMapper;
import com.tj.crypto.trading.paper.persistence.LedgerEntryDO;
import com.tj.crypto.trading.paper.persistence.LedgerMapper;
import com.tj.crypto.trading.paper.persistence.PaperAccountDO;
import com.tj.crypto.trading.paper.persistence.PaperAccountMapper;
import com.tj.crypto.trading.paper.persistence.PaperBalanceDO;
import com.tj.crypto.trading.paper.persistence.PaperBalanceMapper;
import com.tj.crypto.trading.paper.persistence.PaperEquitySnapshotDO;
import com.tj.crypto.trading.paper.persistence.PaperEquitySnapshotMapper;
import com.tj.crypto.trading.paper.persistence.PaperMarkPriceDO;
import com.tj.crypto.trading.paper.persistence.PaperMarkPriceMapper;
import com.tj.crypto.trading.paper.persistence.PaperPositionDO;
import com.tj.crypto.trading.paper.persistence.PaperPositionMapper;
import com.tj.crypto.trading.paper.persistence.PaperTradeDO;
import com.tj.crypto.trading.paper.persistence.PaperTradeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Read-side aggregation for balances, positions, PnL, attribution and TCA. */
@Service
@RequiredArgsConstructor
public class PaperTradingQueryService {
    private static final MathContext MC = new MathContext(18, RoundingMode.HALF_UP);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;
    private final PaperAccountMapper accountMapper;
    private final PaperBalanceMapper balanceMapper;
    private final PaperPositionMapper positionMapper;
    private final PaperTradeMapper tradeMapper;
    private final PaperEquitySnapshotMapper equityMapper;
    private final PaperMarkPriceMapper markMapper;
    private final LedgerMapper ledgerMapper;
    private final OmsOrderMapper orderMapper;
    private final OmsFillMapper fillMapper;

    public PaperAccountDO resolveAccount(String accountId) {
        if (accountId != null && !accountId.isBlank()) return accountMapper.selectById(accountId);
        PaperAccountDO running = accountMapper.selectRunning();
        if (running != null) return running;
        List<PaperAccountDO> recent = accountMapper.selectRecent(1);
        return recent.isEmpty() ? null : recent.get(0);
    }

    public PaperAccountSnapshot snapshot(String accountId) {
        PaperAccountDO account = resolveAccount(accountId);
        if (account == null) return new PaperAccountSnapshot(null, List.of(), List.of(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, 0, 0, null);
        String id = account.getAccountId();
        List<PaperBalanceDO> balances = balanceMapper.selectByAccount(id);
        List<PaperPositionDO> positions = positionMapper.selectByAccount(id);
        List<PaperTradeDO> trades = tradeMapper.selectByAccount(id, 10_000);
        List<OmsFillDO> fills = fillMapper.selectByAccount(id, 10_000);
        BigDecimal realized = trades.stream().map(PaperTradeDO::getGrossPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal unrealized = positions.stream().map(PaperPositionDO::getUnrealizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fees = fills.stream().map(OmsFillDO::getFee)
                .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal net = trades.stream().map(PaperTradeDO::getNetPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add).add(unrealized);
        PaperEquitySnapshotDO latest = equityMapper.selectLatest(id);
        BigDecimal equity = latest == null
                ? baseBalance(balances).add(unrealized) : latest.getEquity();
        return new PaperAccountSnapshot(account, balances, positions, realized, unrealized,
                fees, net, equity, trades.size(), orderMapper.countActiveByAccount(id), latest);
    }

    public List<PaperAccountDO> accounts(int limit) {
        return accountMapper.selectRecent(clamp(limit, 1, 200));
    }

    public List<PaperTradeDO> trades(String accountId, int limit) {
        return tradeMapper.selectByAccount(requireAccount(accountId).getAccountId(), clamp(limit, 1, 5000));
    }

    public List<OmsFillDO> fills(String accountId, int limit) {
        return fillMapper.selectByAccount(requireAccount(accountId).getAccountId(), clamp(limit, 1, 5000));
    }

    public List<OmsOrderDO> activeOrders(String accountId) {
        return orderMapper.selectActiveByAccount(requireAccount(accountId).getAccountId());
    }

    public List<OmsOrderDO> orders(String accountId, int limit) {
        return orderMapper.selectRecentByAccount(requireAccount(accountId).getAccountId(),
                clamp(limit, 1, 5000));
    }

    public List<LedgerEntryDO> ledger(String accountId, int limit) {
        return ledgerMapper.selectEntries(requireAccount(accountId).getAccountId(), clamp(limit, 1, 5000));
    }

    public List<PaperEquitySnapshotDO> equity(String accountId, int limit) {
        return equityMapper.selectByAccount(requireAccount(accountId).getAccountId(), clamp(limit, 1, 20_000));
    }

    public List<PaperMarkPriceDO> marks() {
        return markMapper.selectAll();
    }

    public Map<String, List<PaperAttribution>> attribution(String accountId) {
        List<PaperTradeDO> trades = trades(accountId, 20_000);
        Map<String, List<PaperAttribution>> result = new LinkedHashMap<>();
        result.put("strategy", aggregate(trades, PaperTradeDO::getStrategyId, "strategy"));
        result.put("symbol", aggregate(trades,
                t -> t.getExchange() + ":" + t.getMarketType() + ":" + t.getSymbol(), "symbol"));
        result.put("side", aggregate(trades, PaperTradeDO::getSide, "side"));
        result.put("day", aggregate(trades, t -> DAY.format(
                Instant.ofEpochMilli(t.getClosedAtMs()).atZone(ZoneOffset.UTC)), "day"));
        return result;
    }

    public PaperExecutionQuality executionQuality(String accountId) {
        List<OmsFillDO> fills = fills(accountId, 20_000);
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal notional = BigDecimal.ZERO;
        BigDecimal fees = BigDecimal.ZERO;
        BigDecimal weightedSpread = BigDecimal.ZERO;
        BigDecimal weightedImpact = BigDecimal.ZERO;
        BigDecimal weightedSlippage = BigDecimal.ZERO;
        long makers = 0;
        for (OmsFillDO fill : fills) {
            BigDecimal fillNotional = fill.getFillPrice().multiply(fill.getFillQuantity(), MC);
            quantity = quantity.add(fill.getFillQuantity());
            notional = notional.add(fillNotional);
            fees = fees.add(zero(fill.getFee()));
            weightedSpread = weightedSpread.add(zero(fill.getSpreadBps()).multiply(fillNotional, MC));
            weightedImpact = weightedImpact.add(zero(fill.getImpactBps()).multiply(fillNotional, MC));
            weightedSlippage = weightedSlippage.add(zero(fill.getSlippageBps()).multiply(fillNotional, MC));
            if ("MAKER".equals(fill.getLiquidityRole())) makers++;
        }
        BigDecimal divisor = notional.signum() == 0 ? BigDecimal.ONE : notional;
        BigDecimal makerRatio = fills.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(makers).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(fills.size()), 4, RoundingMode.HALF_UP);
        return new PaperExecutionQuality(fills.size(), quantity, notional, fees,
                weightedSpread.divide(divisor, 8, RoundingMode.HALF_UP),
                weightedImpact.divide(divisor, 8, RoundingMode.HALF_UP),
                weightedSlippage.divide(divisor, 8, RoundingMode.HALF_UP), makerRatio);
    }

    private List<PaperAttribution> aggregate(List<PaperTradeDO> trades,
                                             Function<PaperTradeDO, String> keyFn,
                                             String dimension) {
        Map<String, List<PaperTradeDO>> groups = new LinkedHashMap<>();
        for (PaperTradeDO trade : trades) groups.computeIfAbsent(keyFn.apply(trade), k -> new ArrayList<>()).add(trade);
        return groups.entrySet().stream().map(entry -> summarize(dimension, entry.getKey(), entry.getValue()))
                .sorted((a, b) -> b.netPnl().compareTo(a.netPnl())).toList();
    }

    private PaperAttribution summarize(String dimension, String key, List<PaperTradeDO> trades) {
        int wins = 0;
        int losses = 0;
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal fees = BigDecimal.ZERO;
        BigDecimal funding = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal winsAmount = BigDecimal.ZERO;
        BigDecimal lossesAmount = BigDecimal.ZERO;
        for (PaperTradeDO trade : trades) {
            gross = gross.add(trade.getGrossPnl());
            fees = fees.add(trade.getOpenFee()).add(trade.getCloseFee());
            funding = funding.add(trade.getFunding());
            net = net.add(trade.getNetPnl());
            if (trade.getNetPnl().signum() > 0) {
                wins++;
                winsAmount = winsAmount.add(trade.getNetPnl());
            } else if (trade.getNetPnl().signum() < 0) {
                losses++;
                lossesAmount = lossesAmount.add(trade.getNetPnl().abs());
            }
        }
        int count = trades.size();
        BigDecimal winRate = count == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(wins * 100L).divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP);
        BigDecimal avg = count == 0 ? BigDecimal.ZERO
                : net.divide(BigDecimal.valueOf(count), 8, RoundingMode.HALF_UP);
        BigDecimal profitFactor = lossesAmount.signum() == 0
                ? (winsAmount.signum() == 0 ? BigDecimal.ZERO : new BigDecimal("999999"))
                : winsAmount.divide(lossesAmount, 8, RoundingMode.HALF_UP);
        return new PaperAttribution(dimension, key, count, wins, losses,
                gross, fees, funding, net, winRate, avg, profitFactor);
    }

    private PaperAccountDO requireAccount(String accountId) {
        PaperAccountDO account = resolveAccount(accountId);
        if (account == null) throw new IllegalStateException("No paper account exists");
        return account;
    }

    private BigDecimal baseBalance(List<PaperBalanceDO> balances) {
        return balances.stream().filter(b -> "USDT".equals(b.getAsset())).findFirst()
                .map(PaperBalanceDO::getTotalBalance).orElse(BigDecimal.ZERO);
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
