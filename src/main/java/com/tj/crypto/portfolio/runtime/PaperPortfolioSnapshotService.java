package com.tj.crypto.portfolio.runtime;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.portfolio.analytics.ExposureDirection;
import com.tj.crypto.portfolio.analytics.ExposureValuationModel;
import com.tj.crypto.portfolio.analytics.PortfolioEquitySnapshot;
import com.tj.crypto.portfolio.analytics.PortfolioExposure;
import com.tj.crypto.portfolio.analytics.PortfolioExposureSnapshot;
import com.tj.crypto.portfolio.analytics.SnapshotCompleteness;
import com.tj.crypto.trading.instrument.InstrumentMetadata;
import com.tj.crypto.trading.instrument.InstrumentMetadataService;
import com.tj.crypto.trading.paper.PaperAccountSnapshot;
import com.tj.crypto.trading.paper.PaperTradingQueryService;
import com.tj.crypto.trading.paper.persistence.PaperMarkPriceDO;
import com.tj.crypto.trading.paper.persistence.PaperPositionDO;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Produces complete same-currency PIT portfolio facts from durable Paper account and mark rows. */
@Service
public class PaperPortfolioSnapshotService {
    private final PaperTradingQueryService queryService;
    private final InstrumentMetadataService metadataService;
    private final Clock clock;

    @Autowired
    public PaperPortfolioSnapshotService(PaperTradingQueryService queryService,
                                         InstrumentMetadataService metadataService) {
        this(queryService, metadataService, Clock.systemUTC());
    }

    PaperPortfolioSnapshotService(PaperTradingQueryService queryService,
                                  InstrumentMetadataService metadataService, Clock clock) {
        this.queryService = queryService;
        this.metadataService = metadataService;
        this.clock = clock;
    }

    public PaperPortfolioSnapshotReport snapshot(String accountId, long maxMarkAgeMs) {
        if (maxMarkAgeMs < 0) throw new IllegalArgumentException("maxMarkAgeMs must not be negative");
        long asOf = clock.millis();
        PaperAccountSnapshot account = queryService.snapshot(accountId);
        if (account.account() == null) throw new IllegalStateException("No paper account exists");
        String id = account.account().getAccountId();
        String currency = required(account.account().getBaseCurrency(), "baseCurrency").toUpperCase();
        Map<String, PaperMarkPriceDO> marks = queryService.marks().stream().collect(
                Collectors.toUnmodifiableMap(this::markKey, Function.identity(),
                        (left, right) -> left.getEventTimeMs() >= right.getEventTimeMs() ? left : right));
        List<PaperPositionDO> positions = account.positions().stream()
                .sorted(Comparator.comparing(this::positionKey))
                .toList();
        List<PortfolioExposure> exposures = new ArrayList<>();
        for (PaperPositionDO position : positions) {
            Exchange exchange = Exchange.valueOf(position.getExchange());
            MarketType marketType = MarketType.valueOf(position.getMarketType());
            Instrument instrument = Instrument.of(exchange, marketType, position.getSymbol());
            PaperMarkPriceDO mark = marks.get(markKey(position));
            if (mark == null || mark.getEventTimeMs() == null || mark.getPrice() == null) {
                throw new IllegalStateException("Missing point-in-time mark for " + instrument.id().value());
            }
            if (mark.getEventTimeMs() > asOf || asOf - mark.getEventTimeMs() > maxMarkAgeMs) {
                throw new IllegalStateException("Stale point-in-time mark for " + instrument.id().value());
            }
            InstrumentMetadata metadata = metadataService.require(instrument, mark.getEventTimeMs());
            if (!currency.equalsIgnoreCase(metadata.quoteAsset())
                    || !currency.equalsIgnoreCase(metadata.settleAsset())) {
                throw new IllegalStateException(
                        "Paper portfolio snapshot requires same-currency quote and settlement");
            }
            BigDecimal multiplier = position.getContractMultiplier() == null
                    ? metadata.contractMultiplier() : position.getContractMultiplier();
            String sourceManifest = sha256(mark.getExchange() + '|' + mark.getMarketType() + '|'
                    + mark.getSymbol() + '|' + mark.getEventTimeMs() + '|'
                    + mark.getPrice().toPlainString() + '|' + mark.getSource() + '|'
                    + metadata.metadataId() + '|' + metadata.sourceVersion());
            exposures.add(new PortfolioExposure(id,
                    position.getStrategyId() == null || position.getStrategyId().isBlank()
                            ? "UNATTRIBUTED" : position.getStrategyId(),
                    instrument.id(), "SHORT".equals(position.getSide())
                    ? ExposureDirection.SHORT : ExposureDirection.LONG,
                    position.getQuantity(), mark.getPrice(), multiplier,
                    ExposureValuationModel.LINEAR_QUOTE_SETTLED, currency,
                    mark.getEventTimeMs(), asOf, sourceManifest, SnapshotCompleteness.COMPLETE));
        }
        String snapshotId = "paper-pit-" + sha256(id + '|' + asOf + '|' + positions.size());
        PortfolioExposureSnapshot exposureSnapshot = PortfolioExposureSnapshot.create(
                snapshotId, asOf, currency, Set.of(id), SnapshotCompleteness.COMPLETE, exposures);
        String ledgerManifest = ledgerManifest(account, asOf);
        PortfolioEquitySnapshot equitySnapshot = PortfolioEquitySnapshot.create(
                id, asOf, asOf, currency, account.equity(), ledgerManifest,
                SnapshotCompleteness.COMPLETE);
        return new PaperPortfolioSnapshotReport(exposureSnapshot, equitySnapshot, false);
    }

    private String ledgerManifest(PaperAccountSnapshot account, long asOf) {
        StringBuilder canonical = new StringBuilder("paper-ledger-snapshot-v1|")
                .append(account.account().getAccountId()).append('|').append(asOf)
                .append('|').append(account.equity().toPlainString())
                .append('|').append(account.realizedPnl().toPlainString())
                .append('|').append(account.unrealizedPnl().toPlainString())
                .append('|').append(account.totalFees().toPlainString());
        account.balances().stream().sorted(Comparator.comparing(value -> value.getAsset()))
                .forEach(value -> canonical.append('|').append(value.getAsset()).append('=')
                        .append(value.getTotalBalance().toPlainString()));
        account.positions().stream().sorted(Comparator.comparing(this::positionKey))
                .forEach(value -> canonical.append('|').append(positionKey(value)).append('=')
                        .append(value.getQuantity().toPlainString()).append('@')
                        .append(value.getMarkPrice().toPlainString()));
        return sha256(canonical.toString());
    }

    private String markKey(PaperMarkPriceDO mark) {
        return mark.getExchange() + ':' + mark.getMarketType() + ':' + mark.getSymbol();
    }

    private String markKey(PaperPositionDO position) {
        return position.getExchange() + ':' + position.getMarketType() + ':' + position.getSymbol();
    }

    private String positionKey(PaperPositionDO position) {
        return markKey(position) + ':' + position.getSide() + ':'
                + (position.getStrategyId() == null ? "" : position.getStrategyId());
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
