package com.tj.crypto.trading.venue;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.common.domain.TradeSide;
import com.tj.crypto.execution.model.OrderType;
import com.tj.crypto.risk.KillSwitch;
import com.tj.crypto.risk.RiskProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VenuePreTradeRiskServiceTest {
    private static final long NOW = 1_000_000L;
    private static final BigDecimal PRICE = new BigDecimal("20000");
    private static final Instrument BTC_PERP = Instrument.of(
            Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
    private static final Instrument BTC_SPOT = Instrument.of(
            Exchange.BINANCE, MarketType.SPOT, "BTCUSDT");

    private RiskProperties properties;
    private KillSwitch killSwitch;
    private VenuePreTradeRiskService service;

    @BeforeEach
    void setUp() {
        properties = new RiskProperties();
        properties.setMaxSizePct(new BigDecimal("30"));
        properties.setMaxSymbolExposurePct(new BigDecimal("40"));
        properties.setMaxTotalExposurePct(new BigDecimal("80"));
        properties.setAccountSnapshotMaxAgeMs(5_000);
        properties.setAccountSnapshotMaxFutureSkewMs(1_000);
        killSwitch = new KillSwitch();
        service = new VenuePreTradeRiskService(properties, killSwitch, () -> NOW);
    }

    @Test
    void acceptsFreshOpeningOrderAtTheInclusiveSingleOrderBoundary() {
        VenueOrderCommand command = perpetual(new BigDecimal("0.015"),
                TradeSide.BUY, OrderSide.LONG, false, 10);

        assertThatCode(() -> service.validate(command, PRICE, account(List.of())))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsOrderJustAboveTheSingleOrderBoundary() {
        VenueOrderCommand command = perpetual(new BigDecimal("0.01500001"),
                TradeSide.BUY, OrderSide.LONG, false, 10);

        assertThatThrownBy(() -> service.validate(command, PRICE, account(List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("single-order");
    }

    @Test
    void includesExistingTargetPositionInPerSymbolExposure() {
        VenuePosition existing = position("BTCUSDT", "LONG", "0.015", "20000");
        VenueOrderCommand command = perpetual(new BigDecimal("0.0051"),
                TradeSide.BUY, OrderSide.LONG, false, 10);

        assertThatThrownBy(() -> service.validate(command, PRICE, account(List.of(existing))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("per-symbol");
    }

    @Test
    void includesEveryPositionInGrossPortfolioExposure() {
        VenuePosition otherSymbol = position("ETHUSDT", "LONG", "0.0305", "20000");
        VenueOrderCommand command = perpetual(new BigDecimal("0.01"),
                TradeSide.BUY, OrderSide.LONG, false, 10);

        assertThatThrownBy(() -> service.validate(command, PRICE, account(List.of(otherSymbol))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("portfolio");
    }

    @Test
    void nonReduceOffsettingOrderStillConsumesGrossExposureBudget() {
        VenuePosition existingLong = position("BTCUSDT", "LONG", "0.015", "20000");
        VenueOrderCommand shortOrder = perpetual(new BigDecimal("0.0051"),
                TradeSide.SELL, OrderSide.SHORT, false, 10);

        assertThatThrownBy(() -> service.validate(shortOrder, PRICE, account(List.of(existingLong))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("per-symbol");
    }

    @Test
    void reservesGrossExposureForNonTerminalLiveOrders() {
        VenuePendingExposure pending = new VenuePendingExposure(
                Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT",
                new BigDecimal("0.01"), PRICE, false);
        VenueOrderCommand next = perpetual(new BigDecimal("0.0101"),
                TradeSide.BUY, OrderSide.LONG, false, 10);

        assertThatThrownBy(() -> service.validate(next, PRICE, account(List.of()),
                List.of(pending)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("per-symbol");
    }

    @Test
    void neverCreditsPendingReduceOnlyAsCompletedRiskReduction() {
        VenuePosition existing = position("BTCUSDT", "LONG", "0.015", "20000");
        VenuePendingExposure pendingReduction = new VenuePendingExposure(
                Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT",
                new BigDecimal("0.015"), PRICE, true);
        VenueOrderCommand next = perpetual(new BigDecimal("0.0051"),
                TradeSide.SELL, OrderSide.SHORT, false, 10);

        assertThatThrownBy(() -> service.validate(next, PRICE,
                account(List.of(existing)), List.of(pendingReduction)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("per-symbol");
    }

    @Test
    void binanceShapedZeroMarkDoesNotBlockFullyCoveredEmergencyReduction() {
        VenuePosition binanceAccountPosition = new VenuePosition("BTCUSDT", "LONG",
                new BigDecimal("0.02"), PRICE, BigDecimal.ZERO,
                new BigDecimal("-10"), 10, "CROSS");
        VenueOrderCommand reduce = perpetual(new BigDecimal("0.02"),
                TradeSide.SELL, OrderSide.LONG, true, 10);

        assertThatCode(() -> service.validate(reduce, PRICE,
                account(List.of(binanceAccountPosition)))).doesNotThrowAnyException();
    }

    @Test
    void absentValuationDoesNotBlockFullyCoveredEmergencyReduction() {
        VenuePosition positionWithoutValuation = new VenuePosition("BTC-USDT-SWAP", "SHORT",
                new BigDecimal("0.02"), null, null, null, 0, null);
        VenueOrderCommand reduce = perpetual(new BigDecimal("0.01"),
                TradeSide.BUY, OrderSide.SHORT, true, 1);

        assertThatCode(() -> service.validate(reduce, PRICE,
                account(List.of(positionWithoutValuation)))).doesNotThrowAnyException();
    }

    @Test
    void missingPositionValuationFailsClosedWhenOrderAddsRisk() {
        VenuePosition positionWithoutMark = new VenuePosition("ETHUSDT", "LONG",
                new BigDecimal("0.01"), PRICE, BigDecimal.ZERO,
                BigDecimal.ZERO, 10, "CROSS");
        VenueOrderCommand openingOrder = perpetual(new BigDecimal("0.001"),
                TradeSide.BUY, OrderSide.LONG, false, 10);

        assertThatThrownBy(() -> service.validate(openingOrder, PRICE,
                account(List.of(positionWithoutMark))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mark price");
    }

    @Test
    void reduceOnlyDirectionMustActuallyReduceRequestedPositionSide() {
        VenuePosition existing = position("BTCUSDT", "LONG", "0.02", "20000");
        VenueOrderCommand wrongDirection = perpetual(new BigDecimal("0.01"),
                TradeSide.BUY, OrderSide.LONG, true, 10);

        assertThatThrownBy(() -> service.validate(wrongDirection, PRICE,
                account(List.of(existing))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("direction");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.02000001", "1", "100"})
    void reduceOnlyQuantityCannotCrossThroughThePosition(String quantity) {
        VenuePosition existing = position("BTCUSDT", "SHORT", "0.02", "20000");
        VenueOrderCommand reduce = perpetual(new BigDecimal(quantity),
                TradeSide.BUY, OrderSide.SHORT, true, 10);

        assertThatThrownBy(() -> service.validate(reduce, PRICE, account(List.of(existing))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds the matching live position");
    }

    @Test
    void reduceOnlyRequiresAMatchingSymbolAndSide() {
        VenuePosition existing = position("ETHUSDT", "LONG", "1", "2000");
        VenueOrderCommand reduce = perpetual(new BigDecimal("0.01"),
                TradeSide.SELL, OrderSide.LONG, true, 10);

        assertThatThrownBy(() -> service.validate(reduce, PRICE, account(List.of(existing))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no matching live position");
    }

    @Test
    void closeOnlyRejectsRiskIncreaseButAllowsCoveredReduction() {
        killSwitch.activate(KillSwitch.Mode.CLOSE_ONLY);
        VenuePosition existing = position("BTCUSDT", "LONG", "0.02", "20000");

        assertThatThrownBy(() -> service.validate(perpetual(new BigDecimal("0.001"),
                        TradeSide.BUY, OrderSide.LONG, false, 10), PRICE, account(List.of(existing))))
                .hasMessageContaining("CLOSE_ONLY");
        assertThatCode(() -> service.validate(perpetual(new BigDecimal("0.01"),
                        TradeSide.SELL, OrderSide.LONG, true, 10), PRICE, account(List.of(existing))))
                .doesNotThrowAnyException();
    }

    @Test
    void haltRejectsEvenReduceOnlyAccordingToExistingKillSwitchContract() {
        killSwitch.activate(KillSwitch.Mode.HALT);
        VenuePosition existing = position("BTCUSDT", "LONG", "0.02", "20000");

        assertThatThrownBy(() -> service.validate(perpetual(new BigDecimal("0.01"),
                        TradeSide.SELL, OrderSide.LONG, true, 10), PRICE, account(List.of(existing))))
                .hasMessageContaining("HALT");
    }

    @Test
    void retainsOpeningMarginAvailabilityCheck() {
        VenueOrderCommand command = perpetual(new BigDecimal("0.01"),
                TradeSide.BUY, OrderSide.LONG, false, 1);
        VenueAccountSnapshot lowAvailable = account(List.of(),
                List.of(balance("USDT", "1000", "100", "900")));

        assertThatThrownBy(() -> service.validate(command, PRICE, lowAvailable))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insufficient live available balance");
    }

    @Test
    void rejectsNullStaleFutureAndWrongExchangeSnapshots() {
        VenueOrderCommand command = perpetual(new BigDecimal("0.001"),
                TradeSide.BUY, OrderSide.LONG, false, 10);

        assertThatThrownBy(() -> service.validate(command, PRICE, null))
                .hasMessageContaining("snapshot is required");
        assertThatThrownBy(() -> service.validate(command, PRICE,
                snapshot("BINANCE", NOW - 5_001, List.of(usdt()), List.of())))
                .hasMessageContaining("stale");
        assertThatThrownBy(() -> service.validate(command, PRICE,
                snapshot("BINANCE", NOW + 1_001, List.of(usdt()), List.of())))
                .hasMessageContaining("future");
        assertThatThrownBy(() -> service.validate(command, PRICE,
                snapshot("OKX", NOW, List.of(usdt()), List.of())))
                .hasMessageContaining("does not match");
    }

    @Test
    void acceptsCaseInsensitiveVenueCodeAndFreshnessBoundaries() {
        VenueOrderCommand command = perpetual(new BigDecimal("0.001"),
                TradeSide.BUY, OrderSide.LONG, false, 10);

        assertThatCode(() -> service.validate(command, PRICE,
                snapshot("binance", NOW - 5_000, List.of(usdt()), List.of())))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.validate(command, PRICE,
                snapshot("BINANCE", NOW + 1_000, List.of(usdt()), List.of())))
                .doesNotThrowAnyException();
    }

    @Test
    void incompleteOrInternallyInconsistentBalancesFailClosed() {
        VenueOrderCommand command = perpetual(new BigDecimal("0.001"),
                TradeSide.BUY, OrderSide.LONG, false, 10);

        assertThatThrownBy(() -> service.validate(command, PRICE,
                snapshot("BINANCE", NOW, null, List.of())))
                .hasMessageContaining("balances are required");
        assertThatThrownBy(() -> service.validate(command, PRICE,
                snapshot("BINANCE", NOW, List.of(usdt()), null)))
                .hasMessageContaining("positions are required");
        assertThatThrownBy(() -> service.validate(command, PRICE,
                snapshot("BINANCE", NOW,
                        Arrays.asList(usdt(), null), List.of())))
                .hasMessageContaining("null balance");
        assertThatThrownBy(() -> service.validate(command, PRICE,
                snapshot("BINANCE", NOW,
                        List.of(balance("USDT", "1000", "900", "200")), List.of())))
                .hasMessageContaining("components exceed total");
    }

    @Test
    void missingQuoteBalanceFailsClosedInsteadOfAssumingZero() {
        VenueOrderCommand command = perpetual(new BigDecimal("0.001"),
                TradeSide.BUY, OrderSide.LONG, false, 10);

        assertThatThrownBy(() -> service.validate(command, PRICE,
                snapshot("BINANCE", NOW,
                        List.of(balance("BTC", "1", "1", "0")), List.of())))
                .hasMessageContaining("missing balance for USDT");
    }

    @Test
    void spotReductionUsesInventoryAndDoesNotConsumeOpeningExposureBudget() {
        properties.setMaxSizePct(new BigDecimal("0.01"));
        properties.setMaxSymbolExposurePct(new BigDecimal("0.01"));
        properties.setMaxTotalExposurePct(new BigDecimal("0.01"));
        VenueOrderCommand reduce = spot(new BigDecimal("0.4"), TradeSide.SELL, true);
        VenueAccountSnapshot snapshot = account(List.of(), List.of(
                usdt(), balance("BTC", "0.5", "0.5", "0")));

        assertThatCode(() -> service.validate(reduce, PRICE, snapshot))
                .doesNotThrowAnyException();
    }

    @Test
    void spotReduceOnlyCannotBuyOrSellMoreThanInventory() {
        VenueAccountSnapshot snapshot = account(List.of(), List.of(
                usdt(), balance("BTC", "0.5", "0.5", "0")));

        assertThatThrownBy(() -> service.validate(
                spot(new BigDecimal("0.1"), TradeSide.BUY, true), PRICE, snapshot))
                .hasMessageContaining("BUY cannot be reduce-only");
        assertThatThrownBy(() -> service.validate(
                spot(new BigDecimal("0.50001"), TradeSide.SELL, true), PRICE, snapshot))
                .hasMessageContaining("exceeds existing inventory");
    }

    @Test
    void spotRiskIncreaseFailsClosedWhenPortfolioHasUnpricedAssets() {
        VenueAccountSnapshot snapshot = account(List.of(), List.of(
                usdt(), balance("BTC", "0", "0", "0"),
                balance("ETH", "1", "1", "0")));

        assertThatThrownBy(() -> service.validate(
                spot(new BigDecimal("0.001"), TradeSide.BUY, false), PRICE, snapshot))
                .hasMessageContaining("without a trusted valuation");
    }

    private VenueOrderCommand perpetual(BigDecimal quantity, TradeSide tradeSide,
                                        OrderSide positionSide, boolean reduceOnly, int leverage) {
        return command(BTC_PERP, quantity, tradeSide, positionSide, reduceOnly, leverage);
    }

    private VenueOrderCommand spot(BigDecimal quantity, TradeSide tradeSide, boolean reduceOnly) {
        return command(BTC_SPOT, quantity, tradeSide,
                tradeSide == TradeSide.BUY ? OrderSide.LONG : OrderSide.SHORT,
                reduceOnly, 1);
    }

    private VenueOrderCommand command(Instrument instrument, BigDecimal quantity,
                                      TradeSide tradeSide, OrderSide positionSide,
                                      boolean reduceOnly, int leverage) {
        return new VenueOrderCommand("account-1", "client-1", instrument, tradeSide,
                positionSide, OrderType.MARKET, quantity, null, reduceOnly, leverage, "CROSS");
    }

    private VenueAccountSnapshot account(List<VenuePosition> positions) {
        return account(positions, List.of(usdt()));
    }

    private VenueAccountSnapshot account(List<VenuePosition> positions,
                                         List<VenueBalance> balances) {
        return snapshot("BINANCE", NOW, balances, positions);
    }

    private VenueAccountSnapshot snapshot(String exchange, long eventTimeMs,
                                          List<VenueBalance> balances,
                                          List<VenuePosition> positions) {
        return new VenueAccountSnapshot(exchange, eventTimeMs, balances, positions);
    }

    private VenueBalance usdt() {
        return balance("USDT", "1000", "1000", "0");
    }

    private VenueBalance balance(String asset, String total, String available, String locked) {
        return new VenueBalance(asset, new BigDecimal(total), new BigDecimal(available),
                new BigDecimal(locked));
    }

    private VenuePosition position(String symbol, String side, String quantity, String markPrice) {
        return new VenuePosition(symbol, side, new BigDecimal(quantity), new BigDecimal(markPrice),
                new BigDecimal(markPrice), BigDecimal.ZERO, 10, "CROSS");
    }
}
