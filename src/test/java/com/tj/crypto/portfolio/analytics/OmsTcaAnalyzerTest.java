package com.tj.crypto.portfolio.analytics;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.InstrumentId;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.storage.entity.OmsFillDO;
import com.tj.crypto.storage.entity.OmsOrderDO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OmsTcaAnalyzerTest {
    private static final InstrumentId BTC = new InstrumentId(Exchange.BINANCE,
            MarketType.PERPETUAL, "BTCUSDT");
    private final OmsTcaAnalyzer analyzer = new OmsTcaAnalyzer();

    @Test
    void computesArrivalSlippageFeesLatencyParticipationAndImplementationShortfall() {
        OmsOrderDO order = order("BUY", "10");
        List<OmsFillDO> fills = List.of(
                fill("f-1", "4", "101", 1_100, "1", "USDT", "100"),
                fill("f-2", "4", "102", 1_200, "1", "USDT", "200"));
        List<TcaMarketMark> marks = List.of(
                mark(1_000, 1_000, "100", "0"),
                mark(1_000, 1_100, "101", "50"),
                mark(1_100, 1_200, "102", "50"),
                mark(1_200, 1_300, "105", "100"));
        reconcile(order, "8", "101.5", "PARTIALLY_FILLED", null);

        TcaReport report = analyze(order, fills, marks, List.of(), 1_300);

        assertThat(report.filledQuantity()).isEqualByComparingTo("8");
        assertThat(report.fillRatio()).isEqualByComparingTo("0.8");
        assertThat(report.arrivalPrice()).isEqualByComparingTo("100");
        assertThat(report.volumeWeightedFillPrice()).isEqualByComparingTo("101.5");
        assertThat(report.arrivalSlippageAmount()).isEqualByComparingTo("12");
        assertThat(report.arrivalSlippageBps()).isEqualByComparingTo("150");
        assertThat(report.reportedSlippageBps()).isEqualByComparingTo("150");
        assertThat(report.feesInQuoteCurrency()).isEqualByComparingTo("2");
        assertThat(report.opportunityCost()).isEqualByComparingTo("10");
        assertThat(report.implementationShortfall()).isEqualByComparingTo("24");
        assertThat(report.implementationShortfallBps()).isEqualByComparingTo("240");
        assertThat(report.firstFillLatencyMs()).isEqualTo(100);
        assertThat(report.quantityWeightedFillLatencyMs()).isEqualTo(150);
        assertThat(report.lastObservedFillLatencyMs()).isEqualTo(200);
        assertThat(report.completionLatencyMs()).isNull();
        assertThat(report.marketVolumeQuantity()).isEqualByComparingTo("200");
        assertThat(report.participationRate()).isEqualByComparingTo("0.04");
        assertThat(report.completeness()).isEqualTo(SnapshotCompleteness.COMPLETE);
    }

    @Test
    void sellPriceImprovementProducesNegativeCostAndConvertsFeeCurrency() {
        OmsOrderDO order = order("SELL", "5");
        OmsFillDO fill = fill("f-1", "5", "101", 1_100, "0.01", "BNB", "-100");
        List<TcaMarketMark> marks = List.of(mark(1_000, 1_000, "100", "0"),
                mark(1_000, 1_100, "101", "50"));
        reconcile(order, "5", "101", "FILLED", 1_100L);

        TcaReport report = analyze(order, List.of(fill), marks,
                List.of(rate("BNB", 1_000, "200")), 1_100);

        assertThat(report.arrivalSlippageAmount()).isEqualByComparingTo("-5");
        assertThat(report.feesInQuoteCurrency()).isEqualByComparingTo("2");
        assertThat(report.opportunityCost()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.implementationShortfall()).isEqualByComparingTo("-3");
        assertThat(report.implementationShortfallBps()).isEqualByComparingTo("-60");
        assertThat(report.completionLatencyMs()).isEqualTo(100L);
    }

    @Test
    void requiresPointInTimeArrivalMarkEvenWhenOmsCarriesArrivalPrice() {
        OmsOrderDO order = order("BUY", "1");
        OmsFillDO fill = fill("f-1", "1", "101", 1_100, "0", null, "100");
        fill.setArrivalPrice(bd("100"));

        reconcile(order, "1", "101", "FILLED", 1_100L);
        assertThatThrownBy(() -> analyze(order, List.of(fill),
                List.of(mark(1_000, 1_100, "101", "10")), List.of(), 1_100))
                .hasMessageContaining("arrival market mark");
    }

    @Test
    void incompleteOrInconsistentOmsAndMarkFactsFailClosed() {
        OmsOrderDO order = order("BUY", "1");
        OmsFillDO overfill = fill("f-1", "2", "100", 1_100, "0", null, "0");
        List<TcaMarketMark> marks = List.of(mark(1_000, 1_000, "100", "0"),
                mark(1_000, 1_100, "100", "10"));
        assertThatThrownBy(() -> analyze(order, List.of(overfill), marks, List.of(), 1_100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceed requested");

        OmsFillDO foreignFee = fill("f-2", "1", "100", 1_100, "1", "BNB", "0");
        reconcile(order, "1", "100", "FILLED", 1_100L);
        assertThatThrownBy(() -> analyze(order, List.of(foreignFee), marks, List.of(), 1_100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("point-in-time fee-to-quote");

        assertThatThrownBy(() -> analyze(order,
                List.of(fill("f-3", "1", "100", 1_100, "0", null, "0")),
                List.of(mark(1_000, 1_000, "100", "0")), List.of(), 1_100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("market-volume");

        OmsFillDO conflictingArrival = fill("f-4", "1", "100", 1_100, "0", null, "0");
        conflictingArrival.setArrivalPrice(bd("99"));
        assertThatThrownBy(() -> analyze(order, List.of(conflictingArrival), marks, List.of(), 1_100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disagree on order arrival price");
    }

    @Test
    void legacyIncompleteAndContractInputsFailClosed() {
        OmsOrderDO order = order("BUY", "1");
        OmsFillDO fill = fill("f-1", "1", "100", 1_100, "0", null, "0");
        List<TcaMarketMark> marks = List.of(mark(1_000, 1_000, "100", "0"),
                mark(1_000, 1_100, "100", "10"));
        reconcile(order, "1", "100", "FILLED", 1_100L);

        assertThatThrownBy(() -> analyzer.analyze(order, List.of(fill), marks, List.of(),
                1_100, 1_100, 1_000, "USDT", TcaQuantityUnit.BASE_ASSET_QUANTITY))
                .hasMessageContaining("NOT_VERIFIED");
        TcaInputSnapshot incomplete = snapshot(1_100, SnapshotCompleteness.INCOMPLETE);
        assertThatThrownBy(() -> analyzer.analyze(incomplete, order, List.of(fill), marks, List.of(),
                1_100, 1_100, 1_000, "USDT", TcaQuantityUnit.BASE_ASSET_QUANTITY))
                .hasMessageContaining("incomplete");
        assertThatThrownBy(() -> analyzer.analyze(snapshot(1_100, SnapshotCompleteness.COMPLETE),
                order, List.of(fill), marks, List.of(), 1_100, 1_100, 1_000,
                "USDT", TcaQuantityUnit.CONTRACTS))
                .hasMessageContaining("contract multiplier");
    }

    private OmsOrderDO order(String tradeSide, String quantity) {
        OmsOrderDO order = new OmsOrderDO();
        order.setOrderId("order-1");
        order.setAccountId("paper-1");
        order.setStrategyId("golden");
        order.setExchange("BINANCE");
        order.setMarketType("PERPETUAL");
        order.setSymbol("BTCUSDT");
        order.setTradeSide(tradeSide);
        order.setQuantity(bd(quantity));
        order.setCreatedAtMs(900L);
        order.setSubmittedAtMs(1_000L);
        return order;
    }

    private void reconcile(OmsOrderDO order, String filled, String average, String status, Long filledAt) {
        order.setFilledQuantity(bd(filled));
        order.setAvgFillPrice(bd(average));
        order.setStatus(status);
        order.setFilledAtMs(filledAt);
    }

    private OmsFillDO fill(String id, String quantity, String price, long time,
                           String fee, String feeCurrency, String slippageBps) {
        OmsFillDO fill = new OmsFillDO();
        fill.setFillId(id);
        fill.setAccountId("paper-1");
        fill.setStrategyId("golden");
        fill.setOrderId("order-1");
        fill.setEventId("event-" + id);
        fill.setExchangeTradeId("trade-" + id);
        fill.setFillQuantity(bd(quantity));
        fill.setFillPrice(bd(price));
        fill.setFillTime(time);
        fill.setFee(bd(fee));
        fill.setFeeCurrency(feeCurrency == null ? "USDT" : feeCurrency);
        fill.setSlippageBps(bd(slippageBps));
        return fill;
    }

    private TcaMarketMark mark(long start, long end, String price, String volume) {
        return new TcaMarketMark(BTC, start, end, end, bd(price), bd(volume),
                TcaQuantityUnit.BASE_ASSET_QUANTITY, "mark-" + start + '-' + end);
    }

    private TcaFeeConversionRate rate(String currency, long time, String value) {
        return new TcaFeeConversionRate(currency, "USDT", time, time, bd(value),
                "rate-" + currency + '-' + time);
    }

    private TcaReport analyze(OmsOrderDO order, List<OmsFillDO> fills,
                              List<TcaMarketMark> marks, List<TcaFeeConversionRate> rates,
                              long endTime) {
        return analyzer.analyze(snapshot(endTime, SnapshotCompleteness.COMPLETE),
                order, fills, marks, rates, endTime, endTime, 1_000,
                "USDT", TcaQuantityUnit.BASE_ASSET_QUANTITY);
    }

    private TcaInputSnapshot snapshot(long cutoff, SnapshotCompleteness completeness) {
        return new TcaInputSnapshot("tca-snapshot", cutoff, cutoff, cutoff, cutoff,
                "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64), completeness);
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
