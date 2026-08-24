package com.tj.crypto.autonomy.capital;

import java.util.Objects;

/** Exact account/venue/instrument/strategy binding for one risk envelope. */
public record RiskScope(
        String accountId,
        Venue venue,
        Instrument instrument,
        String strategyId
) implements Comparable<RiskScope> {
    public RiskScope {
        accountId = CapitalValues.id(accountId, "accountId", 192);
        Objects.requireNonNull(venue, "venue");
        Objects.requireNonNull(instrument, "instrument");
        strategyId = CapitalValues.id(strategyId, "strategyId", 192);
    }

    public String canonicalId() {
        return CapitalValues.canonicalId("risk-scope-v1", accountId, venue.id(),
                instrument.boundCanonicalId(), strategyId);
    }

    @Override
    public int compareTo(RiskScope other) {
        int result = accountId.compareTo(other.accountId);
        if (result != 0) return result;
        result = venue.compareTo(other.venue);
        if (result != 0) return result;
        result = instrument.compareTo(other.instrument);
        if (result != 0) return result;
        return strategyId.compareTo(other.strategyId);
    }
}
