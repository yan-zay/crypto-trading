package com.tj.crypto.trading.venue;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.common.domain.TradeSide;
import com.tj.crypto.risk.KillSwitch;
import com.tj.crypto.risk.RiskProperties;
import com.tj.crypto.trading.venue.riskreservation.LiveRiskReservationBudget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Deterministic live pre-trade checks against a freshly signed venue account snapshot.
 *
 * <p>The service deliberately treats incomplete or ambiguous account state as unsafe. Exposure is
 * gross notional: a non-reduce-only order consumes its full notional budget even when it might
 * offset another position. Only a directionally valid and fully covered reduce-only order may
 * bypass the exposure and opening-margin limits.</p>
 */
@Service
public class VenuePreTradeRiskService {
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final int CALCULATION_SCALE = 18;

    private final RiskProperties riskProperties;
    private final KillSwitch killSwitch;
    private final LongSupplier currentTimeMs;

    @Autowired
    public VenuePreTradeRiskService(RiskProperties riskProperties, KillSwitch killSwitch) {
        this(riskProperties, killSwitch, System::currentTimeMillis);
    }

    VenuePreTradeRiskService(RiskProperties riskProperties, KillSwitch killSwitch,
                             LongSupplier currentTimeMs) {
        this.riskProperties = riskProperties;
        this.killSwitch = killSwitch;
        this.currentTimeMs = currentTimeMs;
    }

    public void validate(VenueOrderCommand command, BigDecimal referencePrice,
                         VenueAccountSnapshot account) {
        validate(command, referencePrice, account, List.of());
    }

    public void validate(VenueOrderCommand command, BigDecimal referencePrice,
                         VenueAccountSnapshot account,
                         List<VenuePendingExposure> pendingExposures) {
        validateAndCreateBudget(command, referencePrice, account, pendingExposures);
    }

    /**
     * Validates the signed venue truth and returns absolute limits for the database-atomic
     * reservation transaction. The returned current exposure excludes pending OMS orders;
     * those are read again under the account/exchange database lock.
     */
    public LiveRiskReservationBudget validateAndCreateBudget(
            VenueOrderCommand command, BigDecimal referencePrice,
            VenueAccountSnapshot account, List<VenuePendingExposure> pendingExposures) {
        require(command != null, "Live order command is required");
        require(referencePrice != null && referencePrice.signum() > 0,
                "A positive live reference price is required");
        enforceKillSwitch(command);

        SnapshotView snapshot = validateSnapshot(command, account);
        List<VenuePendingExposure> reservations = validatePendingExposures(
                command, pendingExposures);
        BigDecimal notional = command.quantity().multiply(referencePrice);

        if (command.instrument().marketType() == MarketType.SPOT) {
            validateSpot(command, referencePrice, notional, snapshot);
            if (command.reduceOnly()) {
                return reductionBudget(command, referencePrice, account.eventTimeMs());
            }
            return validateExposureLimits(command, referencePrice, notional, snapshot,
                    reservations, account.eventTimeMs());
        }

        require(command.instrument().marketType() == MarketType.PERPETUAL,
                "Unsupported live market type: " + command.instrument().marketType());
        require(command.positionSide() != null, "Perpetual live order requires positionSide");

        if (command.reduceOnly()) {
            validatePerpetualReduction(command, snapshot.positions());
            return reductionBudget(command, referencePrice, account.eventTimeMs());
        }

        validateOpeningDirection(command);
        BigDecimal requiredMargin = notional.divide(BigDecimal.valueOf(command.leverage()),
                CALCULATION_SCALE, RoundingMode.UP);
        requireAvailable(snapshot, command.instrument().quoteAsset(), requiredMargin);
        return validateExposureLimits(command, referencePrice, notional, snapshot,
                reservations, account.eventTimeMs());
    }

    private List<VenuePendingExposure> validatePendingExposures(
            VenueOrderCommand command, List<VenuePendingExposure> source) {
        require(source != null, "Pending live exposures are required");
        for (VenuePendingExposure pending : source) {
            require(pending != null, "Pending live exposures contain a null entry");
            require(pending.exchange() == command.instrument().exchange(),
                    "Pending live exposure exchange does not match the order account");
            require(pending.marketType() != null, "Pending live exposure market type is required");
            require(pending.symbol() != null && !pending.symbol().isBlank(),
                    "Pending live exposure symbol is required");
            requirePositive(pending.remainingQuantity(), "pending remaining quantity");
            requirePositive(pending.referencePrice(), "pending reference price");
        }
        return List.copyOf(source);
    }

    private void enforceKillSwitch(VenueOrderCommand command) {
        KillSwitch.Mode mode = killSwitch.getMode();
        if (mode == KillSwitch.Mode.HALT) {
            throw new IllegalStateException("Kill switch is HALT");
        }
        if (mode == KillSwitch.Mode.CLOSE_ONLY && !command.reduceOnly()) {
            throw new IllegalStateException("Kill switch is CLOSE_ONLY");
        }
    }

    private SnapshotView validateSnapshot(VenueOrderCommand command, VenueAccountSnapshot account) {
        require(account != null, "Live account snapshot is required");
        require(account.exchange() != null && !account.exchange().isBlank(),
                "Live account snapshot exchange is required");

        Exchange expectedExchange = command.instrument().exchange();
        String actualExchange = account.exchange().trim();
        boolean exchangeMatches = expectedExchange.name().equalsIgnoreCase(actualExchange)
                || expectedExchange.getCode().equalsIgnoreCase(actualExchange);
        require(exchangeMatches, "Live account snapshot exchange does not match the order");

        long maxAgeMs = riskProperties.getAccountSnapshotMaxAgeMs();
        long maxFutureSkewMs = riskProperties.getAccountSnapshotMaxFutureSkewMs();
        require(maxAgeMs > 0, "Account snapshot max age must be positive");
        require(maxFutureSkewMs >= 0, "Account snapshot future skew must not be negative");
        require(account.eventTimeMs() > 0, "Live account snapshot event time is required");

        long now = currentTimeMs.getAsLong();
        if (account.eventTimeMs() > now) {
            require(account.eventTimeMs() - now <= maxFutureSkewMs,
                    "Live account snapshot timestamp is too far in the future");
        } else {
            require(now - account.eventTimeMs() <= maxAgeMs,
                    "Live account snapshot is stale");
        }

        require(account.balances() != null && !account.balances().isEmpty(),
                "Live account snapshot balances are required");
        require(account.positions() != null, "Live account snapshot positions are required");

        Map<String, VenueBalance> balances = validateBalances(account.balances());
        List<VenuePosition> positions = validatePositions(account.positions());
        return new SnapshotView(balances, positions);
    }

    private Map<String, VenueBalance> validateBalances(List<VenueBalance> source) {
        Map<String, VenueBalance> balances = new HashMap<>();
        for (VenueBalance balance : source) {
            require(balance != null, "Live account snapshot contains a null balance");
            require(balance.asset() != null && !balance.asset().isBlank(),
                    "Live account balance asset is required");
            requireNonNegative(balance.total(), "balance total");
            requireNonNegative(balance.available(), "balance available");
            requireNonNegative(balance.locked(), "balance locked");
            require(balance.total().compareTo(balance.available()) >= 0,
                    "Live account balance total is below available");
            require(balance.total().compareTo(balance.locked()) >= 0,
                    "Live account balance total is below locked");
            require(balance.available().add(balance.locked()).compareTo(balance.total()) <= 0,
                    "Live account balance components exceed total");

            String asset = normalizeAsset(balance.asset());
            require(balances.putIfAbsent(asset, balance) == null,
                    "Live account snapshot contains duplicate balance asset: " + asset);
        }
        return Map.copyOf(balances);
    }

    private List<VenuePosition> validatePositions(List<VenuePosition> source) {
        Set<String> identities = new HashSet<>();
        for (VenuePosition position : source) {
            require(position != null, "Live account snapshot contains a null position");
            require(position.symbol() != null && !position.symbol().isBlank(),
                    "Live position symbol is required");
            require(position.side() != null && !position.side().isBlank(),
                    "Live position side is required");
            String side = position.side().trim().toUpperCase(Locale.ROOT);
            require("LONG".equals(side) || "SHORT".equals(side),
                    "Live position side must be LONG or SHORT");
            requirePositive(position.quantity(), "position quantity");
            // Some signed venue account endpoints do not supply a mark price (Binance's account
            // response is one example). Direction and quantity are still sufficient to prove a
            // fully covered reduction. Valuation fields are required later if an order adds risk.
            requireOptionalNonNegative(position.entryPrice(), "position entry price");
            requireOptionalNonNegative(position.markPrice(), "position mark price");

            String identity = canonicalSymbol(position.symbol()) + ':' + side;
            require(identities.add(identity),
                    "Live account snapshot contains a duplicate position: " + identity);
        }
        return List.copyOf(source);
    }

    private void validateSpot(VenueOrderCommand command, BigDecimal referencePrice,
                              BigDecimal notional, SnapshotView snapshot) {
        require(command.instrument().baseAsset() != null && !command.instrument().baseAsset().isBlank(),
                "Spot live order requires a base asset");
        require(command.instrument().quoteAsset() != null && !command.instrument().quoteAsset().isBlank(),
                "Spot live order requires a quote asset");

        if (command.tradeSide() == TradeSide.BUY) {
            require(!command.reduceOnly(), "Spot BUY cannot be reduce-only");
            requireAvailable(snapshot, command.instrument().quoteAsset(), notional);
        } else {
            if (command.reduceOnly()) {
                validateSpotReduction(command, snapshot);
                requireAvailable(snapshot, command.instrument().baseAsset(), command.quantity());
                return;
            }
            requireAvailable(snapshot, command.instrument().baseAsset(), command.quantity());
        }
    }

    private void validateSpotReduction(VenueOrderCommand command, SnapshotView snapshot) {
        require(command.tradeSide() == TradeSide.SELL,
                "Spot reduce-only order must SELL existing inventory");
        VenueBalance balance = requireBalance(snapshot, command.instrument().baseAsset());
        require(command.quantity().compareTo(balance.total()) <= 0,
                "Spot reduce-only quantity exceeds existing inventory");
    }

    private void validateOpeningDirection(VenueOrderCommand command) {
        boolean opensLong = command.positionSide() == OrderSide.LONG
                && command.tradeSide() == TradeSide.BUY;
        boolean opensShort = command.positionSide() == OrderSide.SHORT
                && command.tradeSide() == TradeSide.SELL;
        require(opensLong || opensShort,
                "Non-reduce-only perpetual order direction is inconsistent with positionSide");
    }

    private void validatePerpetualReduction(VenueOrderCommand command,
                                             List<VenuePosition> positions) {
        TradeSide requiredTradeSide = command.positionSide() == OrderSide.LONG
                ? TradeSide.SELL : TradeSide.BUY;
        require(command.tradeSide() == requiredTradeSide,
                "Reduce-only direction does not reduce the requested position side");

        BigDecimal reducibleQuantity = positions.stream()
                .filter(position -> matchesSymbol(position.symbol(), command.instrument().symbol()))
                .filter(position -> command.positionSide().name().equalsIgnoreCase(position.side()))
                .map(VenuePosition::quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        require(reducibleQuantity.signum() > 0,
                "Reduce-only order has no matching live position");
        require(command.quantity().compareTo(reducibleQuantity) <= 0,
                "Reduce-only quantity exceeds the matching live position");
    }

    private LiveRiskReservationBudget validateExposureLimits(
            VenueOrderCommand command, BigDecimal referencePrice,
            BigDecimal orderNotional, SnapshotView snapshot,
            List<VenuePendingExposure> pendingExposures, long snapshotEventTimeMs) {
        BigDecimal equity = conservativeEquity(command, snapshot);
        Exposure currentExposure = currentExposure(command, referencePrice, snapshot);
        Exposure pendingExposure = pendingExposure(command, referencePrice, pendingExposures);

        requireWithinLimit("single-order", orderNotional, equity,
                riskProperties.getMaxSizePct());
        requireWithinLimit("per-symbol", currentExposure.symbol()
                .add(pendingExposure.symbol()).add(orderNotional), equity,
                riskProperties.getMaxSymbolExposurePct());
        requireWithinLimit("portfolio", currentExposure.total()
                .add(pendingExposure.total()).add(orderNotional), equity,
                riskProperties.getMaxTotalExposurePct());

        return new LiveRiskReservationBudget(command.accountId(),
                command.instrument().exchange(), command.instrument().marketType(),
                command.instrument().symbol(), command.quantity(), referencePrice, orderNotional,
                currentExposure.symbol(), currentExposure.total(),
                absoluteLimit(equity, riskProperties.getMaxSizePct()),
                absoluteLimit(equity, riskProperties.getMaxSymbolExposurePct()),
                absoluteLimit(equity, riskProperties.getMaxTotalExposurePct()),
                true, snapshotEventTimeMs);
    }

    private LiveRiskReservationBudget reductionBudget(
            VenueOrderCommand command, BigDecimal referencePrice, long snapshotEventTimeMs) {
        return LiveRiskReservationBudget.reduction(command.accountId(),
                command.instrument().exchange(), command.instrument().marketType(),
                command.instrument().symbol(), command.quantity(), referencePrice,
                snapshotEventTimeMs);
    }

    private BigDecimal absoluteLimit(BigDecimal equity, BigDecimal limitPct) {
        requirePositive(limitPct, "gross exposure percentage");
        return equity.multiply(limitPct).divide(ONE_HUNDRED,
                CALCULATION_SCALE, RoundingMode.DOWN);
    }

    private Exposure pendingExposure(VenueOrderCommand command, BigDecimal referencePrice,
                                     List<VenuePendingExposure> pendingExposures) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal symbol = BigDecimal.ZERO;
        for (VenuePendingExposure pending : pendingExposures) {
            // A reduce-only order is not credited as risk reduction: it may be cancelled or fail.
            if (pending.reduceOnly()) continue;
            boolean target = pending.marketType() == command.instrument().marketType()
                    && matchesSymbol(pending.symbol(), command.instrument().symbol());
            BigDecimal price = target
                    ? pending.referencePrice().max(referencePrice)
                    : pending.referencePrice();
            BigDecimal notional = pending.remainingQuantity().multiply(price);
            total = total.add(notional);
            if (target) symbol = symbol.add(notional);
        }
        return new Exposure(symbol, total);
    }

    private BigDecimal conservativeEquity(VenueOrderCommand command, SnapshotView snapshot) {
        VenueBalance quoteBalance = requireBalance(snapshot, command.instrument().quoteAsset());
        BigDecimal negativeUnrealizedPnl = snapshot.positions().stream()
                .map(position -> {
                    require(position.unrealizedPnl() != null,
                            "Live position unrealized PnL is required to add risk");
                    return position.unrealizedPnl();
                })
                .filter(pnl -> pnl.signum() < 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal equity = quoteBalance.total().add(negativeUnrealizedPnl);
        require(equity.signum() > 0, "Live account equity must be positive");
        return equity;
    }

    private Exposure currentExposure(VenueOrderCommand command, BigDecimal referencePrice,
                                     SnapshotView snapshot) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal symbol = BigDecimal.ZERO;
        for (VenuePosition position : snapshot.positions()) {
            requirePositive(position.markPrice(),
                    "position mark price is required to add risk");
            require(position.leverage() > 0,
                    "Live position leverage must be positive to add risk");
            require(position.marginMode() != null && !position.marginMode().isBlank(),
                    "Live position margin mode is required to add risk");
            boolean targetSymbol = matchesSymbol(position.symbol(), command.instrument().symbol());
            BigDecimal valuationPrice = targetSymbol
                    ? position.markPrice().max(referencePrice)
                    : position.markPrice();
            BigDecimal positionNotional = position.quantity().multiply(valuationPrice);
            total = total.add(positionNotional);
            if (targetSymbol) symbol = symbol.add(positionNotional);
        }

        if (command.instrument().marketType() == MarketType.SPOT) {
            require(snapshot.positions().isEmpty(),
                    "Spot account snapshot must not contain derivative positions");
            String baseAsset = normalizeAsset(command.instrument().baseAsset());
            String quoteAsset = normalizeAsset(command.instrument().quoteAsset());
            for (Map.Entry<String, VenueBalance> entry : snapshot.balances().entrySet()) {
                if (!entry.getKey().equals(baseAsset) && !entry.getKey().equals(quoteAsset)
                        && entry.getValue().total().signum() > 0) {
                    throw new IllegalStateException(
                            "Spot portfolio contains an asset without a trusted valuation: "
                                    + entry.getKey());
                }
            }
            VenueBalance baseBalance = snapshot.balances().get(baseAsset);
            if (baseBalance != null) {
                BigDecimal inventoryNotional = baseBalance.total().multiply(referencePrice);
                total = total.add(inventoryNotional);
                symbol = symbol.add(inventoryNotional);
            }
        }
        return new Exposure(symbol, total);
    }

    private void requireWithinLimit(String limitName, BigDecimal notional, BigDecimal equity,
                                    BigDecimal limitPct) {
        requirePositive(limitPct, limitName + " exposure percentage");
        if (notional.multiply(ONE_HUNDRED).compareTo(equity.multiply(limitPct)) > 0) {
            throw new IllegalStateException("Live order exceeds " + limitName + " risk limit");
        }
    }

    private void requireAvailable(SnapshotView snapshot, String asset, BigDecimal required) {
        VenueBalance balance = requireBalance(snapshot, asset);
        if (balance.available().compareTo(required) < 0) {
            throw new IllegalStateException("Insufficient live available balance for " + asset);
        }
    }

    private VenueBalance requireBalance(SnapshotView snapshot, String asset) {
        require(asset != null && !asset.isBlank(), "Required live balance asset is missing");
        VenueBalance balance = snapshot.balances().get(normalizeAsset(asset));
        require(balance != null, "Live account snapshot is missing balance for " + asset);
        return balance;
    }

    private void requirePositive(BigDecimal value, String field) {
        require(value != null && value.signum() > 0, "Live " + field + " must be positive");
    }

    private void requireNonNegative(BigDecimal value, String field) {
        require(value != null && value.signum() >= 0, "Live " + field + " must not be negative");
    }

    private void requireOptionalNonNegative(BigDecimal value, String field) {
        require(value == null || value.signum() >= 0, "Live " + field + " must not be negative");
    }

    private boolean matchesSymbol(String venueSymbol, String instrumentSymbol) {
        return canonicalSymbol(venueSymbol).equals(canonicalSymbol(instrumentSymbol));
    }

    private String canonicalSymbol(String symbol) {
        String canonical = symbol.trim().toUpperCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "")
                .replace("/", "");
        return canonical.endsWith("SWAP")
                ? canonical.substring(0, canonical.length() - "SWAP".length())
                : canonical;
    }

    private String normalizeAsset(String asset) {
        return asset.trim().toUpperCase(Locale.ROOT);
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record SnapshotView(Map<String, VenueBalance> balances,
                                List<VenuePosition> positions) {}

    private record Exposure(BigDecimal symbol, BigDecimal total) {}
}
