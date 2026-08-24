package com.tj.crypto.trading.instrument;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InstrumentMetadataVersionServiceTest {

    @Test
    void settlementAssetChangeAlwaysCreatesANewVersion() {
        InstrumentMetadataMapper mapper = mock(InstrumentMetadataMapper.class);
        InstrumentMetadataDO current = current();
        when(mapper.selectActive("BINANCE", "PERPETUAL", "BTCUSDT", 200)).thenReturn(current);
        when(mapper.closeVersion(1L, 200)).thenReturn(1);
        InstrumentMetadataVersionService service = new InstrumentMetadataVersionService(mapper);

        boolean changed = service.apply(snapshot("BTC"), 200);

        assertThat(changed).isTrue();
        verify(mapper).closeVersion(1L, 200);
        ArgumentCaptor<InstrumentMetadataDO> inserted = ArgumentCaptor.forClass(InstrumentMetadataDO.class);
        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue().getSettleAsset()).isEqualTo("BTC");
    }

    private InstrumentMetadataDO current() {
        InstrumentMetadataDO value = new InstrumentMetadataDO();
        value.setMetadataId(1L);
        value.setVenueSymbol("BTCUSDT");
        value.setBaseAsset("BTC");
        value.setQuoteAsset("USDT");
        value.setSettleAsset("USDT");
        value.setInstrumentStatus("TRADING");
        value.setTickSize(decimal("0.1"));
        value.setStepSize(decimal("0.001"));
        value.setMinQuantity(decimal("0.001"));
        value.setMaxQuantity(decimal("100"));
        value.setMinNotional(decimal("5"));
        value.setContractMultiplier(BigDecimal.ONE);
        value.setMakerFeeRate(BigDecimal.ZERO);
        value.setTakerFeeRate(BigDecimal.ZERO);
        value.setMaxLeverage(100);
        value.setMaintenanceMarginRate(decimal("0.005"));
        value.setValidFromMs(100L);
        return value;
    }

    private InstrumentRuleSnapshot snapshot(String settleAsset) {
        return new InstrumentRuleSnapshot(Exchange.BINANCE, MarketType.PERPETUAL,
                "BTCUSDT", "BTCUSDT", "BTC", "USDT", settleAsset, "TRADING",
                decimal("0.1"), decimal("0.001"), decimal("0.001"), decimal("100"),
                decimal("5"), BigDecimal.ONE, "test-v2");
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
