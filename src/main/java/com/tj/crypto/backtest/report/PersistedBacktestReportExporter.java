package com.tj.crypto.backtest.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.tj.crypto.admin.dto.BacktestResultDTO;
import com.tj.crypto.admin.dto.BacktestTradeDTO;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;

/** Exports a persisted detailed backtest read model without rerunning the strategy. */
@Component
public class PersistedBacktestReportExporter {

    private final ObjectMapper objectMapper;

    public PersistedBacktestReportExporter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public ReportArtifact export(BacktestResultDTO result, String requestedFormat) {
        String format = requestedFormat == null ? "json"
                : requestedFormat.toLowerCase(Locale.ROOT);
        String baseName = "backtest-" + result.id();
        return switch (format) {
            case "json" -> new ReportArtifact(toJson(result), "application/json", baseName + ".json");
            case "csv" -> new ReportArtifact(toCsv(result), "text/csv", baseName + ".csv");
            case "md", "markdown" -> new ReportArtifact(
                    toMarkdown(result), "text/markdown", baseName + ".md");
            default -> throw new IllegalArgumentException(
                    "Unsupported report format: " + requestedFormat);
        };
    }

    private String toJson(BacktestResultDTO result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot export backtest JSON", e);
        }
    }

    private String toCsv(BacktestResultDTO result) {
        StringBuilder csv = new StringBuilder();
        csv.append("run_id,strategy,exchange,market_type,symbol,timeframe,side,entry_time,")
                .append("exit_time,entry_price,exit_price,quantity,pnl,pnl_pct,fees\n");
        for (BacktestTradeDTO trade : result.trades()) {
            csv.append(cell(result.id())).append(',')
                    .append(cell(result.strategyName())).append(',')
                    .append(cell(result.exchange())).append(',')
                    .append(cell(result.marketType())).append(',')
                    .append(cell(result.symbol())).append(',')
                    .append(cell(result.timeframe())).append(',')
                    .append(cell(trade.side())).append(',')
                    .append(trade.entryTime()).append(',')
                    .append(trade.exitTime()).append(',')
                    .append(trade.entryPrice()).append(',')
                    .append(trade.exitPrice()).append(',')
                    .append(trade.quantity()).append(',')
                    .append(trade.pnl()).append(',')
                    .append(trade.pnlPct()).append(',')
                    .append(trade.fees()).append('\n');
        }
        return csv.toString();
    }

    private String toMarkdown(BacktestResultDTO result) {
        StringBuilder report = new StringBuilder("# Backtest Report\n\n");
        report.append("## Scope\n\n")
                .append("| Field | Value |\n|---|---|\n")
                .append("| Run ID | ").append(result.id()).append(" |\n")
                .append("| Strategy | ").append(result.strategyName()).append(" |\n")
                .append("| Market | ").append(result.exchange()).append(" / ")
                .append(result.marketType()).append(" |\n")
                .append("| Instrument | ").append(result.symbol()).append(" / ")
                .append(result.timeframe()).append(" |\n")
                .append("| Period | ").append(result.startDate()).append(" to ")
                .append(result.endDate()).append(" |\n\n")
                .append("## Performance\n\n")
                .append("| Metric | Value |\n|---|---:|\n")
                .append("| Total return | ").append(percent(result.totalReturnPct())).append(" |\n")
                .append("| Annualized return | ").append(percent(result.annualizedReturnPct())).append(" |\n")
                .append("| Maximum drawdown | ").append(percent(result.maxDrawdownPct())).append(" |\n")
                .append("| Win rate | ").append(percent(result.winRatePct())).append(" |\n")
                .append("| Sharpe | ").append(result.sharpeRatio()).append(" |\n")
                .append("| Sortino | ").append(result.sortinoRatio()).append(" |\n")
                .append("| Calmar | ").append(result.calmarRatio()).append(" |\n")
                .append("| Profit factor | ").append(result.profitFactor()).append(" |\n")
                .append("| Trades | ").append(result.totalTrades()).append(" |\n")
                .append("| Signals | ").append(result.signalCount()).append(" |\n")
                .append("| Fees | ").append(result.totalFees()).append(" |\n\n");

        if (!result.monthlyReturnsPct().isEmpty()) {
            report.append("## Monthly Returns\n\n| Month | Return |\n|---|---:|\n");
            for (Map.Entry<String, BigDecimal> entry : result.monthlyReturnsPct().entrySet()) {
                report.append("| ").append(entry.getKey()).append(" | ")
                        .append(percent(entry.getValue())).append(" |\n");
            }
            report.append('\n');
        }
        report.append("## Reproducibility\n\n```json\n")
                .append(result.strategyConfigJson()).append("\n```\n\n")
                .append("```json\n").append(result.assumptionsJson()).append("\n```\n");
        return report.toString();
    }

    private String percent(BigDecimal fraction) {
        return fraction.multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP) + "%";
    }

    private String cell(String value) {
        if (value == null) return "";
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    public record ReportArtifact(String content, String mediaType, String filename) {}
}
