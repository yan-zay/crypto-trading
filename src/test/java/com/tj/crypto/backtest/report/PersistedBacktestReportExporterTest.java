package com.tj.crypto.backtest.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.admin.dto.BacktestResultDTO;
import com.tj.crypto.admin.dto.BacktestTradeDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PersistedBacktestReportExporterTest {

    private final PersistedBacktestReportExporter exporter =
            new PersistedBacktestReportExporter(new ObjectMapper());

    @Test
    void exportsMarkdownCsvAndJson() {
        BacktestResultDTO result = result();

        assertThat(exporter.export(result, "markdown").content())
                .contains("# Backtest Report", "Total return", "12.50%", "strategy");
        assertThat(exporter.export(result, "csv").content())
                .contains("run_id,strategy", "\"run-1\"", "LONG");
        assertThat(exporter.export(result, "json").content())
                .contains("\"id\" : \"run-1\"");
    }

    private BacktestResultDTO result() {
        return new BacktestResultDTO(
                "run-1", "strategy", "binance", "PERPETUAL", "BTCUSDT", "1h",
                "2026-01-01", "2026-02-01", new BigDecimal("10000"),
                new BigDecimal("11250"), new BigDecimal("0.125"),
                new BigDecimal("0.8"), new BigDecimal("0.2"), new BigDecimal("0.6"),
                new BigDecimal("1.2"), new BigDecimal("1.4"), new BigDecimal("4"),
                new BigDecimal("1000"), 1, 2, 1, 0, 1, 0,
                new BigDecimal("0.01"), BigDecimal.ZERO, new BigDecimal("1.5"),
                new BigDecimal("10"), "{\"name\":\"strategy\"}", "{}",
                Map.of("2026-01", new BigDecimal("0.125")), List.of(), List.of(),
                List.of(new BacktestTradeDTO(1L, 2L, "LONG", BigDecimal.ONE,
                        BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE,
                        new BigDecimal("0.1"), BigDecimal.ZERO)));
    }
}
