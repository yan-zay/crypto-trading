package com.tj.crypto.marketdata.normalize;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.marketdata.model.LiquidationEvent;
import com.tj.crypto.pojo.dto.LiquidationOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CoinglassLiquidationNormalizer 单元测试。
 * 使用本地构造的 LiquidationOrder，不依赖网络。
 */
class CoinglassLiquidationNormalizerTest {

    private CoinglassLiquidationNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new CoinglassLiquidationNormalizer();
    }

    private LiquidationOrder createOrder(String symbol, int side, BigDecimal price,
                                          BigDecimal volUsd, long time, String exName) {
        LiquidationOrder order = new LiquidationOrder();
        order.setSymbol(symbol);
        order.setSide(side);
        order.setPrice(price);
        order.setVolUsd(volUsd);
        order.setTime(time);
        order.setExName(exName);
        return order;
    }

    @Nested
    @DisplayName("正常爆仓数据解析")
    class NormalLiquidation {

        @Test
        @DisplayName("应正确解析多头爆仓为 LiquidationEvent")
        void shouldParseLongLiquidation() {
            LiquidationOrder order = createOrder("BTCUSDT", 1,
                    new BigDecimal("16721.50"),
                    new BigDecimal("500000"),
                    1672515780000L, "Binance");

            LiquidationEvent result = normalizer.normalize(order);

            assertThat(result).isNotNull();
            assertThat(result.instrument().exchange()).isEqualTo(Exchange.COINGLASS);
            assertThat(result.instrument().marketType()).isEqualTo(MarketType.PERPETUAL);
            assertThat(result.instrument().symbol()).isEqualTo("BTCUSDT");
            assertThat(result.instrument().baseAsset()).isEqualTo("BTC");
            assertThat(result.instrument().quoteAsset()).isEqualTo("USDT");
            assertThat(result.side()).isEqualTo(OrderSide.LONG);
            assertThat(result.price()).isEqualByComparingTo(new BigDecimal("16721.50"));
            assertThat(result.quantityUsd()).isEqualByComparingTo(new BigDecimal("500000"));
            assertThat(result.exchangeName()).isEqualTo("Binance");
            assertThat(result.metadata().source()).isEqualTo(Exchange.COINGLASS);
            assertThat(result.metadata().exchangeTimestamp()).isEqualTo(1672515780000L);
        }

        @Test
        @DisplayName("应正确解析空头爆仓")
        void shouldParseShortLiquidation() {
            LiquidationOrder order = createOrder("ETHUSDT", 2,
                    new BigDecimal("1200.00"),
                    new BigDecimal("1000000"),
                    1672515780000L, "OKX");

            LiquidationEvent result = normalizer.normalize(order);

            assertThat(result).isNotNull();
            assertThat(result.side()).isEqualTo(OrderSide.SHORT);
            assertThat(result.instrument().symbol()).isEqualTo("ETHUSDT");
            assertThat(result.instrument().baseAsset()).isEqualTo("ETH");
            assertThat(result.exchangeName()).isEqualTo("OKX");
        }

        @Test
        @DisplayName("应正确解析 SOL 爆仓")
        void shouldParseSolLiquidation() {
            LiquidationOrder order = createOrder("SOLUSDT", 1,
                    new BigDecimal("100.50"),
                    new BigDecimal("250000"),
                    1672515780000L, "Binance");

            LiquidationEvent result = normalizer.normalize(order);

            assertThat(result).isNotNull();
            assertThat(result.instrument().symbol()).isEqualTo("SOLUSDT");
            assertThat(result.instrument().baseAsset()).isEqualTo("SOL");
        }
    }

    @Nested
    @DisplayName("异常处理")
    class ErrorHandling {

        @Test
        @DisplayName("无效 side 值应默认为 LONG 且不抛异常")
        void shouldDefaultToLongForInvalidSide() {
            LiquidationOrder order = createOrder("BTCUSDT", 99,
                    new BigDecimal("16721.50"),
                    new BigDecimal("500000"),
                    1672515780000L, "Binance");

            LiquidationEvent result = normalizer.normalize(order);

            assertThat(result).isNotNull();
            assertThat(result.side()).isEqualTo(OrderSide.LONG);
        }

        @Test
        @DisplayName("null symbol 应返回 null（异常被捕获）")
        void shouldReturnNullForNullSymbol() {
            LiquidationOrder order = createOrder(null, 1,
                    new BigDecimal("16721.50"),
                    new BigDecimal("500000"),
                    1672515780000L, "Binance");

            LiquidationEvent result = normalizer.normalize(order);

            // Instrument.of 在 symbol 为 null 时抛 NPE，被 normalizer 捕获并返回 null
            assertThat(result).isNull();
        }
    }
}
