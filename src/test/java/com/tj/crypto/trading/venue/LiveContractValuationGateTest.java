package com.tj.crypto.trading.venue;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.trading.instrument.InstrumentMetadata;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveContractValuationGateTest {

    @Test
    void rejectsNonUnitDerivativeMultiplierUntilRiskFactsCarryContractTerms() {
        assertThatThrownBy(() -> LiveOrderService.requireSupportedContractValuation(
                metadata(MarketType.PERPETUAL, "0.01"), false))
                .isInstanceOf(LiveAuthorizationException.class)
                .hasMessageContaining("Non-unit contract multipliers");
    }

    @Test
    void acceptsUnitMultiplierForSupportedLinearAndSpotProducts() {
        assertThatCode(() -> LiveOrderService.requireSupportedContractValuation(
                metadata(Exchange.BINANCE, MarketType.PERPETUAL, "1", "USDT"), false))
                .doesNotThrowAnyException();
        assertThatCode(() -> LiveOrderService.requireSupportedContractValuation(
                metadata(Exchange.OKX, MarketType.SPOT, "1", "USDT"), false))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingOrNonPositiveMultiplier() {
        assertThatThrownBy(() -> LiveOrderService.requireSupportedContractValuation(
                metadata(MarketType.PERPETUAL, "0"), false))
                .isInstanceOf(LiveAuthorizationException.class)
                .hasMessageContaining("positive, trusted contract multiplier");
    }

    @Test
    void blocksOkxPerpetualAndNonQuoteSettledProductsUntilTermsAreVersioned() {
        assertThatThrownBy(() -> LiveOrderService.requireSupportedContractValuation(
                metadata(Exchange.OKX, MarketType.PERPETUAL, "1", "USDT"), false))
                .hasMessageContaining("ctVal/ctMult/ctType");
        assertThatThrownBy(() -> LiveOrderService.requireSupportedContractValuation(
                metadata(Exchange.BINANCE, MarketType.PERPETUAL, "1", "BTC"), false))
                .hasMessageContaining("quote-settled");
    }

    @Test
    void unsupportedOkxContractTermsStillAllowAnExactReduceOnlyPath() {
        assertThatCode(() -> LiveOrderService.requireSupportedContractValuation(
                metadata(Exchange.OKX, MarketType.PERPETUAL, "0.01", "BTC"), true))
                .doesNotThrowAnyException();
    }

    private InstrumentMetadata metadata(MarketType marketType, String multiplier) {
        return metadata(Exchange.OKX, marketType, multiplier, "USDT");
    }

    private InstrumentMetadata metadata(Exchange exchange, MarketType marketType,
                                        String multiplier, String settleAsset) {
        BigDecimal one = BigDecimal.ONE;
        return new InstrumentMetadata(1, exchange, marketType, "BTCUSDT", "BTC-USDT-SWAP",
                "BTC", "USDT", settleAsset, "TRADING", one, one, one,
                new BigDecimal("1000000"), one, new BigDecimal(multiplier),
                BigDecimal.ZERO, BigDecimal.ZERO, 100, new BigDecimal("0.01"),
                8, 1, null, "test");
    }
}
