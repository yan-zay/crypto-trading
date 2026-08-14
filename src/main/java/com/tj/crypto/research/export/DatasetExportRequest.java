package com.tj.crypto.research.export;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;

public record DatasetExportRequest(
        ExportType type,
        ExportFormat format,
        Exchange exchange,
        MarketType marketType,
        String symbol,
        String timeframe,
        Long from,
        Long to,
        String accountId
) {
    public DatasetExportRequest {
        if (type == null || format == null) throw new IllegalArgumentException("type and format are required");
    }

    public enum ExportType { CANONICAL_BARS, PAPER_TRADES, OMS_FILLS, ACCOUNT_LEDGER }
    public enum ExportFormat { CSV, JSONL }
}
