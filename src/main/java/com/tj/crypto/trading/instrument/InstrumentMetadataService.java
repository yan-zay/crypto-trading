package com.tj.crypto.trading.instrument;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.config.properties.MarketUniverseProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** Resolves the exact instrument rules that were effective at an event time. */
@Service
@RequiredArgsConstructor
public class InstrumentMetadataService {
    private final InstrumentMetadataMapper mapper;
    private final MarketUniverseProperties marketUniverse;

    public InstrumentMetadata require(Exchange exchange, MarketType marketType,
                                      String symbol, long asOf) {
        marketUniverse.validate(exchange, marketType, symbol);
        String normalized = MarketUniverseProperties.normalizeSymbol(symbol);
        InstrumentMetadataDO found = mapper.selectActive(
                exchange.name(), marketType.name(), normalized, asOf);
        if (found == null) {
            throw new IllegalStateException("No active instrument metadata for "
                    + exchange + ":" + marketType + ":" + normalized);
        }
        return toDomain(found);
    }

    public InstrumentMetadata require(Instrument instrument, long asOf) {
        return require(instrument.exchange(), instrument.marketType(), instrument.symbol(), asOf);
    }

    public List<InstrumentMetadata> current() {
        return mapper.selectCurrent().stream().map(this::toDomain).toList();
    }

    private InstrumentMetadata toDomain(InstrumentMetadataDO source) {
        return new InstrumentMetadata(
                source.getMetadataId(), Exchange.valueOf(source.getExchange()),
                MarketType.valueOf(source.getMarketType()), source.getSymbol(),
                source.getVenueSymbol(), source.getBaseAsset(), source.getQuoteAsset(),
                source.getSettleAsset(), source.getInstrumentStatus(), source.getTickSize(),
                source.getStepSize(), source.getMinQuantity(), source.getMaxQuantity(),
                source.getMinNotional(), source.getContractMultiplier(),
                source.getMakerFeeRate(), source.getTakerFeeRate(), source.getMaxLeverage(),
                source.getMaintenanceMarginRate(), source.getFundingIntervalHours(),
                source.getValidFromMs(), source.getValidToMs(), source.getSourceVersion());
    }
}
