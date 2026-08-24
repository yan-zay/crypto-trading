package com.tj.crypto.marketdata.archive;

import com.tj.crypto.marketdata.model.MarketEvent;
import com.tj.crypto.marketdata.model.RawMarketMessage;

/**
 * 实时 raw-first WAL 端口。调用方必须先等待 retainRaw fsync receipt，才能解析并持久 canonical 事件。
 */
public interface LiveMarketIngestJournal {
    RawJournalReceipt retainRaw(RawMarketMessage rawMessage);

    CanonicalJournalReceipt retainCanonical(String rawMessageId, int rawOrdinal, MarketEvent event);

    NormalizationFailureReceipt recordNormalizationFailure(String rawMessageId, String code, String detail);

    JournalReplay replay();

    RealtimeCaptureCapability realtimeCaptureCapability();
}
