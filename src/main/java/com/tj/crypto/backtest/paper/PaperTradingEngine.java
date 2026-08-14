package com.tj.crypto.backtest.paper;

import com.tj.crypto.common.domain.TradeSide;
import com.tj.crypto.event.MarketEventBus;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderType;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.risk.RiskProperties;
import com.tj.crypto.strategy.core.SignalEvent;
import com.tj.crypto.strategy.core.SignalListener;
import com.tj.crypto.strategy.core.SignalType;
import com.tj.crypto.trading.instrument.InstrumentMetadata;
import com.tj.crypto.trading.instrument.InstrumentMetadataService;
import com.tj.crypto.trading.paper.PaperAccountLifecycleService;
import com.tj.crypto.trading.paper.PaperAccountSnapshot;
import com.tj.crypto.trading.paper.PaperMarkRequest;
import com.tj.crypto.trading.paper.PaperMarketDataService;
import com.tj.crypto.trading.paper.PaperLiquidationService;
import com.tj.crypto.trading.paper.PaperOrderMatchingService;
import com.tj.crypto.trading.paper.PaperOrderRequest;
import com.tj.crypto.trading.paper.PaperOrderService;
import com.tj.crypto.trading.paper.PaperTradingQueryService;
import com.tj.crypto.trading.paper.persistence.PaperMarkPriceDO;
import com.tj.crypto.trading.paper.persistence.PaperPositionDO;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Signal and market-data adapter for the persistent paper brokerage.
 * Account, orders and fills remain recoverable because this class owns no trading state.
 */
@Slf4j
@Component
public class PaperTradingEngine implements SignalListener {
    private static final MathContext MC = new MathContext(18, RoundingMode.HALF_UP);
    private final MarketEventBus eventBus;
    private final PaperAccountLifecycleService lifecycleService;
    private final PaperMarketDataService marketDataService;
    private final PaperOrderMatchingService matchingService;
    private final PaperLiquidationService liquidationService;
    private final PaperOrderService orderService;
    private final PaperTradingQueryService queryService;
    private final InstrumentMetadataService metadataService;
    private final RiskProperties riskProperties;

    public PaperTradingEngine(MarketEventBus eventBus,
                              PaperAccountLifecycleService lifecycleService,
                              PaperMarketDataService marketDataService,
                              PaperOrderMatchingService matchingService,
                              PaperLiquidationService liquidationService,
                              PaperOrderService orderService,
                              PaperTradingQueryService queryService,
                              InstrumentMetadataService metadataService,
                              RiskProperties riskProperties) {
        this.eventBus = eventBus;
        this.lifecycleService = lifecycleService;
        this.marketDataService = marketDataService;
        this.matchingService = matchingService;
        this.liquidationService = liquidationService;
        this.orderService = orderService;
        this.queryService = queryService;
        this.metadataService = metadataService;
        this.riskProperties = riskProperties;
    }

    @PostConstruct
    public void subscribeMarketPrices() {
        eventBus.subscribe(BarEvent.class, this::onBar);
    }

    public void start(BigDecimal initialBalance) {
        lifecycleService.start(initialBalance, null, null);
    }

    public void stop() {
        PaperAccountSnapshot snapshot = queryService.snapshot(null);
        if (snapshot.account() == null) return;
        lifecycleService.stop(snapshot.account().getAccountId(), null);
    }

    @Override
    public void onSignal(SignalEvent signal) {
        if (signal.type() == SignalType.HOLD) return;
        PaperAccountSnapshot account = queryService.snapshot(null);
        if (!account.running()) return;
        try {
            PaperMarkPriceDO mark = marketDataService.require(signal.instrument().exchange(),
                    signal.instrument().marketType(), signal.instrument().symbol());
            InstrumentMetadata metadata = metadataService.require(signal.instrument(),
                    Math.max(mark.getEventTimeMs(), System.currentTimeMillis()));
            TradeSide side = signal.type() == SignalType.BUY ? TradeSide.BUY : TradeSide.SELL;
            BigDecimal quantity = signalQuantity(signal, account, mark, metadata, side);
            if (quantity.signum() <= 0) return;
            String idempotencyKey = UUID.nameUUIDFromBytes((signal.strategyName() + ":"
                    + signal.instrument().id().value() + ":" + signal.type() + ":"
                    + signal.timestamp()).getBytes(StandardCharsets.UTF_8)).toString();
            Order order = orderService.place(new PaperOrderRequest(
                    account.account().getAccountId(), idempotencyKey, signal.strategyName(),
                    signal.instrument().exchange(), signal.instrument().marketType(),
                    signal.instrument().symbol(), side, OrderType.MARKET, quantity,
                    null, 1, false, idempotencyKey));
            log.info("[PAPER] strategy={} order={} status={} filled={}/{}",
                    signal.strategyName(), order.orderId(), order.status(),
                    order.filledQuantity(), order.quantity());
        } catch (RuntimeException e) {
            log.error("Paper signal execution failed: strategy={}, instrument={}",
                    signal.strategyName(), signal.instrument().id(), e);
        }
    }

    public boolean isRunning() {
        return lifecycleService.running() != null;
    }

    private void onBar(BarEvent bar) {
        if (!bar.closed()) return;
        long eventTime = bar.metadata().exchangeTimestamp() > 0
                ? bar.metadata().exchangeTimestamp() : bar.metadata().receivedTimestamp();
        PaperMarkPriceDO mark = marketDataService.update(new PaperMarkRequest(
                bar.instrument().exchange(), bar.instrument().marketType(), bar.instrument().symbol(),
                bar.close(), bar.high(), bar.low(), bar.volume(), eventTime), "BAR_EVENT");
        liquidationService.evaluate(mark);
        matchingService.match(mark);
    }

    private BigDecimal signalQuantity(SignalEvent signal, PaperAccountSnapshot account,
                                      PaperMarkPriceDO mark, InstrumentMetadata metadata,
                                      TradeSide side) {
        PaperPositionDO position = account.positions().stream()
                .filter(p -> p.getExchange().equals(signal.instrument().exchange().name())
                        && p.getMarketType().equals(signal.instrument().marketType().name())
                        && p.getSymbol().equals(signal.instrument().symbol()))
                .findFirst().orElse(null);
        boolean closes = position != null
                && ((side == TradeSide.BUY && "SHORT".equals(position.getSide()))
                || (side == TradeSide.SELL && "LONG".equals(position.getSide())));
        if (closes) return position.getQuantity();
        BigDecimal available = account.balances().stream()
                .filter(b -> metadata.settleAsset().equals(b.getAsset()))
                .map(com.tj.crypto.trading.paper.persistence.PaperBalanceDO::getAvailableBalance)
                .findFirst().orElse(BigDecimal.ZERO);
        BigDecimal confidence = signal.confidence() == null
                ? BigDecimal.ONE : signal.confidence().max(BigDecimal.ZERO).min(BigDecimal.ONE);
        BigDecimal budget = available.multiply(riskProperties.getMaxSizePct(), MC)
                .divide(BigDecimal.valueOf(100), MC).multiply(confidence, MC);
        return metadata.alignQuantity(budget.divide(mark.getPrice(), MC)
                .divide(metadata.contractMultiplier(), MC));
    }
}
