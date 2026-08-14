package com.tj.crypto.trading.paper;

import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.config.properties.MarketUniverseProperties;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/** Persists marks, revalues positions and records the account equity curve. */
@Service
@RequiredArgsConstructor
public class PaperMarketDataService {
    private static final MathContext MC = new MathContext(18, RoundingMode.HALF_UP);
    private final PaperMarkPriceMapper markMapper;
    private final PaperAccountMapper accountMapper;
    private final PaperBalanceMapper balanceMapper;
    private final PaperPositionMapper positionMapper;
    private final PaperEquitySnapshotMapper equityMapper;
    private final MarketUniverseProperties marketUniverse;

    @Transactional
    public PaperMarkPriceDO update(PaperMarkRequest request, String source) {
        marketUniverse.validate(request.exchange(), request.marketType(), request.symbol());
        PaperMarkPriceDO mark = toDO(request, source);
        markMapper.upsert(mark);
        PaperAccountDO account = accountMapper.selectRunning();
        if (account != null) {
            revaluePosition(account.getAccountId(), mark);
            snapshotEquity(account, request.eventTimeMs());
        }
        return markMapper.select(request.exchange().name(), request.marketType().name(),
                MarketUniverseProperties.normalizeSymbol(request.symbol()));
    }

    public PaperMarkPriceDO require(com.tj.crypto.common.domain.Exchange exchange,
                                    MarketType marketType, String symbol) {
        PaperMarkPriceDO mark = markMapper.select(exchange.name(), marketType.name(),
                MarketUniverseProperties.normalizeSymbol(symbol));
        if (mark == null) {
            throw new IllegalStateException("No paper market price for "
                    + exchange + ":" + marketType + ":" + symbol);
        }
        return mark;
    }

    @Transactional
    public void snapshotEquity(PaperAccountDO account, long eventTime) {
        List<PaperBalanceDO> balances = balanceMapper.selectByAccount(account.getAccountId());
        List<PaperPositionDO> positions = positionMapper.selectByAccount(account.getAccountId());
        BigDecimal baseBalance = balances.stream()
                .filter(b -> account.getBaseCurrency().equals(b.getAsset())).findFirst()
                .map(PaperBalanceDO::getTotalBalance).orElse(BigDecimal.ZERO);
        BigDecimal available = balances.stream()
                .filter(b -> account.getBaseCurrency().equals(b.getAsset())).findFirst()
                .map(PaperBalanceDO::getAvailableBalance).orElse(BigDecimal.ZERO);
        BigDecimal locked = balances.stream()
                .filter(b -> account.getBaseCurrency().equals(b.getAsset())).findFirst()
                .map(PaperBalanceDO::getLockedBalance).orElse(BigDecimal.ZERO);
        BigDecimal unrealized = BigDecimal.ZERO;
        BigDecimal spotValue = BigDecimal.ZERO;
        for (PaperPositionDO position : positions) {
            if (MarketType.SPOT.name().equals(position.getMarketType())) {
                spotValue = spotValue.add(position.getMarkPrice().multiply(position.getQuantity(), MC));
            } else {
                unrealized = unrealized.add(position.getUnrealizedPnl());
            }
        }
        PaperEquitySnapshotDO snapshot = new PaperEquitySnapshotDO();
        snapshot.setSnapshotId(UUID.randomUUID().toString());
        snapshot.setAccountId(account.getAccountId());
        snapshot.setEventTimeMs(eventTime);
        snapshot.setBalance(baseBalance);
        snapshot.setAvailableBalance(available);
        snapshot.setLockedMargin(locked);
        snapshot.setUnrealizedPnl(unrealized);
        snapshot.setEquity(baseBalance.add(spotValue).add(unrealized));
        equityMapper.upsert(snapshot);
    }

    private void revaluePosition(String accountId, PaperMarkPriceDO mark) {
        PaperPositionDO position = positionMapper.selectForUpdate(accountId, mark.getExchange(),
                mark.getMarketType(), mark.getSymbol());
        if (position == null) return;
        position.setMarkPrice(mark.getPrice());
        BigDecimal difference = mark.getPrice().subtract(position.getEntryPrice());
        if ("SHORT".equals(position.getSide())) difference = difference.negate();
        position.setUnrealizedPnl(difference.multiply(position.getQuantity(), MC)
                .multiply(position.getContractMultiplier(), MC));
        position.setUpdatedAtMs(mark.getEventTimeMs());
        positionMapper.updatePosition(position);
    }

    private PaperMarkPriceDO toDO(PaperMarkRequest request, String source) {
        PaperMarkPriceDO mark = new PaperMarkPriceDO();
        mark.setExchange(request.exchange().name());
        mark.setMarketType(request.marketType().name());
        mark.setSymbol(MarketUniverseProperties.normalizeSymbol(request.symbol()));
        mark.setPrice(request.price());
        mark.setHighPrice(request.highPrice());
        mark.setLowPrice(request.lowPrice());
        mark.setBaseVolume(request.baseVolume());
        mark.setEventTimeMs(request.eventTimeMs());
        mark.setSource(source == null ? "UNKNOWN" : source);
        return mark;
    }
}
