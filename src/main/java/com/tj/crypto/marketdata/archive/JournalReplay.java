package com.tj.crypto.marketdata.archive;

import java.util.List;

public record JournalReplay(
        List<RawJournalReceipt> rawMessages,
        List<CanonicalJournalReceipt> canonicalEvents,
        List<NormalizationFailureReceipt> normalizationFailures
) {
    public JournalReplay {
        rawMessages = List.copyOf(rawMessages);
        canonicalEvents = List.copyOf(canonicalEvents);
        normalizationFailures = List.copyOf(normalizationFailures);
    }
}
