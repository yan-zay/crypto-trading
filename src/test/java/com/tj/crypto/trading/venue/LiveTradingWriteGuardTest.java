package com.tj.crypto.trading.venue;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.common.domain.TradeSide;
import com.tj.crypto.execution.model.OrderType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveTradingWriteGuardTest {
    @Test
    void staticFlagsNeverAuthorizeLiveCanaryOpeningRisk() {
        PrivateTradingProperties properties = new PrivateTradingProperties();
        properties.getBinance().setEnabled(true);
        properties.getBinance().setWriteEnabled(true);
        LiveTradingWriteGuard guard = new LiveTradingWriteGuard(properties);

        assertThatThrownBy(() -> guard.requireWriteEnabled(Exchange.BINANCE))
                .hasMessageContaining("RESEARCH_ONLY");
        properties.setLiveWriteEnabled(true);
        assertThatThrownBy(() -> guard.requireWriteEnabled(Exchange.BINANCE))
                .hasMessageContaining("RESEARCH_ONLY");
        properties.setOperatingMode(PrivateTradingProperties.OperatingMode.LIVE_CANARY);
        assertThatThrownBy(() -> guard.requireWriteEnabled(Exchange.BINANCE))
                .hasMessageContaining("legal-approval-reference");
        properties.setTargetJurisdiction("WRITTEN-COUNSEL-APPROVED-JURISDICTION");
        properties.setLegalApprovalReference("LEGAL-2026-001");
        assertThatThrownBy(() -> guard.requireWriteEnabled(Exchange.BINANCE))
                .hasMessageContaining("governed command authorization");
        assertThatThrownBy(() -> guard.requireWriteEnabled(Exchange.COINGLASS))
                .hasMessageContaining("Private venue is disabled");
    }

    @Test
    void sandboxRequiresExplicitVenueSimulation() {
        PrivateTradingProperties properties = new PrivateTradingProperties();
        properties.setOperatingMode(PrivateTradingProperties.OperatingMode.SANDBOX);
        properties.setLiveWriteEnabled(true);
        properties.getOkx().setEnabled(true);
        properties.getOkx().setWriteEnabled(true);
        LiveTradingWriteGuard guard = new LiveTradingWriteGuard(properties);

        assertThatThrownBy(() -> guard.requireWriteEnabled(Exchange.OKX))
                .hasMessageContaining("simulated-trading=true");
        properties.getOkx().setSimulatedTrading(true);
        assertThatCode(() -> guard.requireWriteEnabled(Exchange.OKX)).doesNotThrowAnyException();
    }

    @Test
    void sandboxBinanceRejectsProductionOriginsEvenWhenTestnetFlagIsTrue() {
        PrivateTradingProperties properties = new PrivateTradingProperties();
        properties.setOperatingMode(PrivateTradingProperties.OperatingMode.SANDBOX);
        properties.setLiveWriteEnabled(true);
        properties.getBinance().setEnabled(true);
        properties.getBinance().setWriteEnabled(true);
        properties.getBinance().setTestnet(true);
        LiveTradingWriteGuard guard = new LiveTradingWriteGuard(properties);

        assertThatThrownBy(() -> guard.requireWriteEnabled(Exchange.BINANCE))
                .hasMessageContaining("approved HTTPS sandbox origin");

        properties.getBinance().setSpotRestBaseUrl("https://testnet.binance.vision");
        properties.getBinance().setPerpetualRestBaseUrl("https://demo-fapi.binance.com");
        assertThatCode(() -> guard.requireWriteEnabled(Exchange.BINANCE)).doesNotThrowAnyException();

        properties.setUserStreamsEnabled(true);
        assertThatThrownBy(() -> guard.requireWriteEnabled(Exchange.BINANCE))
                .hasMessageContaining("approved WSS sandbox origin");
        properties.getBinance().setSpotUserStreamUrl(
                "wss://stream.testnet.binance.vision/ws");
        properties.getBinance().setPerpetualUserStreamUrl(
                "wss://fstream.binancefuture.com/ws");
        assertThatCode(() -> guard.requireWriteEnabled(Exchange.BINANCE)).doesNotThrowAnyException();
    }

    @Test
    void cancellationCanRemainAvailableAfterGlobalOpeningGateCloses() {
        PrivateTradingProperties properties = new PrivateTradingProperties();
        properties.setOperatingMode(PrivateTradingProperties.OperatingMode.LIVE_CANARY);
        properties.setTargetJurisdiction("COUNSEL-APPROVED");
        properties.setLegalApprovalReference("LEGAL-2026-001");
        properties.getBinance().setEnabled(true);
        properties.getBinance().setWriteEnabled(false);
        LiveTradingWriteGuard guard = new LiveTradingWriteGuard(properties);

        assertThatThrownBy(() -> guard.requireWriteEnabled(Exchange.BINANCE))
                .hasMessageContaining("governed command authorization");
        assertThatCode(() -> guard.requireCancelEnabled(Exchange.BINANCE)).doesNotThrowAnyException();
    }

    @Test
    void liveCanaryMarketOpeningCannotUseCallerSuppliedReferencePrice() {
        PrivateTradingProperties properties = new PrivateTradingProperties();
        properties.setOperatingMode(PrivateTradingProperties.OperatingMode.LIVE_CANARY);
        LiveTradingWriteGuard guard = new LiveTradingWriteGuard(properties);

        assertThatThrownBy(() -> guard.requireTrustedMarketValuation(marketRequest(false)))
                .isInstanceOf(LiveAuthorizationException.class)
                .hasMessageContaining("trusted server-side valuation snapshot");
        assertThatCode(() -> guard.requireTrustedMarketValuation(marketRequest(true)))
                .doesNotThrowAnyException();

        properties.setOperatingMode(PrivateTradingProperties.OperatingMode.SANDBOX);
        assertThatCode(() -> guard.requireTrustedMarketValuation(marketRequest(false)))
                .doesNotThrowAnyException();
    }

    private LiveOrderRequest marketRequest(boolean reduceOnly) {
        return new LiveOrderRequest("account-1", "client-1", "strategy-1", Exchange.BINANCE,
                MarketType.PERPETUAL, "BTCUSDT", reduceOnly ? TradeSide.SELL : TradeSide.BUY,
                OrderSide.LONG, OrderType.MARKET, BigDecimal.ONE, null,
                new BigDecimal("100"), 1, reduceOnly, "ISOLATED", "correlation-1");
    }
}
