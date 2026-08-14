package com.tj.crypto.backtest.engine;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.EventMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

class BacktestAssumptionsTest {

    private final Instrument btcUsdt = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");

    @Test
    @DisplayName("默认假设应使用 CLOSE_ONLY 模式")
    void defaultsShouldUseCloseOnly() {
        BacktestAssumptions defaults = BacktestAssumptions.defaults();

        assertThat(defaults.fillMode()).isEqualTo(BacktestAssumptions.FillMode.CLOSE_ONLY);
        assertThat(defaults.slippageModel()).isEqualTo("FIXED");
        assertThat(defaults.feeModel()).isEqualTo("MAKER_TAKER");
        assertThat(defaults.minNotional()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(defaults.quantityPrecision()).isEqualTo(3);
        assertThat(defaults.pricePrecision()).isEqualTo(2);
        assertThat(defaults.fundingRateEnabled()).isFalse();
    }

    @Test
    @DisplayName("乐观假设应使用 INTERPOLATED 模式")
    void optimisticShouldUseInterpolated() {
        BacktestAssumptions optimistic = BacktestAssumptions.optimistic();

        assertThat(optimistic.fillMode()).isEqualTo(BacktestAssumptions.FillMode.INTERPOLATED);
        assertThat(optimistic.slippageModel()).isEqualTo("FIXED");
        assertThat(optimistic.feeModel()).isEqualTo("MAKER_TAKER");
    }

    @Test
    @DisplayName("FillModel.create 应返回正确的实现类")
    void fillModelCreateShouldReturnCorrectImplementation() {
        FillModel closeOnly = FillModel.create(BacktestAssumptions.FillMode.CLOSE_ONLY);
        FillModel interpolated = FillModel.create(BacktestAssumptions.FillMode.INTERPOLATED);

        assertThat(closeOnly).isInstanceOf(FillModel.CloseOnlyFillModel.class);
        assertThat(interpolated).isInstanceOf(FillModel.InterpolatedFillModel.class);
    }

    @Test
    @DisplayName("CLOSE_ONLY 兼容枚举应使用下一根 K 线开盘价")
    void closeOnlyShouldUseNextBarOpenPrice() {
        FillModel fillModel = FillModel.create(BacktestAssumptions.FillMode.CLOSE_ONLY);
        BarEvent bar = createBar(BigDecimal.valueOf(100), BigDecimal.valueOf(110));

        BigDecimal basePrice = fillModel.calculateBasePrice(bar);

        assertThat(basePrice).isEqualByComparingTo(BigDecimal.valueOf(100));
    }

    @Test
    @DisplayName("INTERPOLATED 应使用下一根 K 线 OHLC4 作为基础价格")
    void interpolatedShouldUseOhlc4Price() {
        FillModel fillModel = FillModel.create(BacktestAssumptions.FillMode.INTERPOLATED);
        BarEvent bar = createBar(BigDecimal.valueOf(100), BigDecimal.valueOf(110));

        BigDecimal basePrice = fillModel.calculateBasePrice(bar);

        // (100 + 110) / 2 = 105
        BigDecimal expected = BigDecimal.valueOf(105).setScale(8, RoundingMode.HALF_UP);
        assertThat(basePrice).isEqualByComparingTo(expected);
    }

    @Test
    @DisplayName("上涨 K 线中 OHLC4 应高于开盘价")
    void interpolatedShouldBeAboveOpenInUptrend() {
        FillModel closeOnly = FillModel.create(BacktestAssumptions.FillMode.CLOSE_ONLY);
        FillModel interpolated = FillModel.create(BacktestAssumptions.FillMode.INTERPOLATED);

        // 上涨行情：open=100, close=110
        BarEvent uptrendBar = createBar(BigDecimal.valueOf(100), BigDecimal.valueOf(110));

        BigDecimal closeOnlyPrice = closeOnly.calculateBasePrice(uptrendBar);
        BigDecimal interpolatedPrice = interpolated.calculateBasePrice(uptrendBar);

        assertThat(interpolatedPrice).isGreaterThan(closeOnlyPrice);
    }

    @Test
    @DisplayName("下跌 K 线中 OHLC4 应低于开盘价")
    void interpolatedShouldBeBelowOpenInDowntrend() {
        FillModel closeOnly = FillModel.create(BacktestAssumptions.FillMode.CLOSE_ONLY);
        FillModel interpolated = FillModel.create(BacktestAssumptions.FillMode.INTERPOLATED);

        // 下跌行情：open=110, close=100
        BarEvent downtrendBar = createBar(BigDecimal.valueOf(110), BigDecimal.valueOf(100));

        BigDecimal closeOnlyPrice = closeOnly.calculateBasePrice(downtrendBar);
        BigDecimal interpolatedPrice = interpolated.calculateBasePrice(downtrendBar);

        assertThat(interpolatedPrice).isLessThan(closeOnlyPrice);
    }

    @Test
    @DisplayName("当 open == close 时，两种模式价格应相同")
    void bothModesShouldBeEqualWhenOpenEqualsClose() {
        FillModel closeOnly = FillModel.create(BacktestAssumptions.FillMode.CLOSE_ONLY);
        FillModel interpolated = FillModel.create(BacktestAssumptions.FillMode.INTERPOLATED);

        BarEvent flatBar = createBar(BigDecimal.valueOf(100), BigDecimal.valueOf(100));

        BigDecimal closeOnlyPrice = closeOnly.calculateBasePrice(flatBar);
        BigDecimal interpolatedPrice = interpolated.calculateBasePrice(flatBar);

        assertThat(closeOnlyPrice).isEqualByComparingTo(interpolatedPrice);
    }

    @Test
    @DisplayName("假设的 JSON 序列化应包含所有字段")
    void assumptionsShouldBeSerializable() throws Exception {
        BacktestAssumptions assumptions = BacktestAssumptions.defaults();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        String json = mapper.writeValueAsString(assumptions);

        assertThat(json).contains("\"fillMode\"");
        assertThat(json).contains("\"CLOSE_ONLY\"");
        assertThat(json).contains("\"slippageModel\"");
        assertThat(json).contains("\"FIXED\"");
        assertThat(json).contains("\"feeModel\"");
        assertThat(json).contains("\"MAKER_TAKER\"");
        assertThat(json).contains("\"fundingRateEnabled\"");
    }

    private BarEvent createBar(BigDecimal open, BigDecimal close) {
        EventMetadata metadata = EventMetadata.of(Exchange.BINANCE, 1_700_000_000_000L);
        return new BarEvent(
                btcUsdt, metadata, Timeframe.M1,
                open, close.max(open), close.min(open), close,
                BigDecimal.valueOf(1000), BigDecimal.valueOf(100000),
                true
        );
    }
}
