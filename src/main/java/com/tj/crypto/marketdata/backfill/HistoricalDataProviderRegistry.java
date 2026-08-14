package com.tj.crypto.marketdata.backfill;

import com.tj.crypto.common.domain.Exchange;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Resolves exchange-specific history providers without hard-coded switch statements. */
@Component
public class HistoricalDataProviderRegistry {

    private final Map<Exchange, ExchangeHistoricalDataProvider> providers;

    public HistoricalDataProviderRegistry(List<ExchangeHistoricalDataProvider> providerList) {
        EnumMap<Exchange, ExchangeHistoricalDataProvider> indexed = new EnumMap<>(Exchange.class);
        for (ExchangeHistoricalDataProvider provider : providerList) {
            ExchangeHistoricalDataProvider previous = indexed.put(provider.exchange(), provider);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate historical provider for " + provider.exchange());
            }
        }
        this.providers = Map.copyOf(indexed);
    }

    public ExchangeHistoricalDataProvider require(Exchange exchange) {
        ExchangeHistoricalDataProvider provider = providers.get(exchange);
        if (provider == null) {
            throw new IllegalArgumentException(
                    "Historical backfill is not implemented for " + exchange);
        }
        return provider;
    }
}
