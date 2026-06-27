package com.tj.crypto.backtest.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.tj.crypto.backtest.engine.BacktestResult;
import com.tj.crypto.backtest.portfolio.Trade;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 回测报告导出器。
 * 支持 Markdown、CSV、JSON 三种格式。
 */
@Component
public class BacktestReportExporter {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("UTC"));

    private final ObjectMapper objectMapper;

    public BacktestReportExporter() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * 导出为 Markdown 格式报告。
     *
     * @param result 回测结果
     * @return Markdown 文本
     */
    public String exportMarkdown(BacktestResult result) {
        PerformanceReport r = result.performanceReport();
        StringBuilder sb = new StringBuilder();

        sb.append("# Backtest Performance Report\n\n");

        // 回测假设
        if (result.assumptions() != null) {
            sb.append("## Backtest Assumptions\n\n");
            sb.append("| Assumption | Value |\n");
            sb.append("|------------|-------|\n");
            sb.append("| Fill Mode | ").append(result.assumptions().fillMode()).append(" |\n");
            sb.append("| Slippage Model | ").append(result.assumptions().slippageModel()).append(" |\n");
            sb.append("| Fee Model | ").append(result.assumptions().feeModel()).append(" |\n");
            sb.append("| Min Notional | ").append(result.assumptions().minNotional()).append(" |\n");
            sb.append("| Quantity Precision | ").append(result.assumptions().quantityPrecision()).append(" |\n");
            sb.append("| Price Precision | ").append(result.assumptions().pricePrecision()).append(" |\n");
            sb.append("| Funding Rate | ").append(result.assumptions().fundingRateEnabled() ? "Enabled" : "Disabled").append(" |\n");
            sb.append("\n");
            if (result.assumptions().fillMode() == com.tj.crypto.backtest.engine.BacktestAssumptions.FillMode.INTERPOLATED) {
                sb.append("> **Warning**: INTERPOLATED fill mode uses (open + close) / 2, which is more optimistic than real trading.\n\n");
            }
        }

        // 概览
        sb.append("## Overview\n\n");
        sb.append("| Metric | Value |\n");
        sb.append("|--------|-------|\n");
        sb.append("| Instrument | ").append(result.config().instrument().symbol()).append(" |\n");
        sb.append("| Timeframe | ").append(result.config().timeframe().getDisplayName()).append(" |\n");
        sb.append("| Period | ").append(DATE_FMT.format(Instant.ofEpochMilli(r.startTime())))
                .append(" ~ ").append(DATE_FMT.format(Instant.ofEpochMilli(r.endTime()))).append(" |\n");
        sb.append("| Initial Balance | ").append(r.initialBalance()).append(" |\n");
        sb.append("| Final Balance | ").append(r.finalBalance()).append(" |\n");
        sb.append("| Total Signals | ").append(result.signals().size()).append(" |\n");
        sb.append("\n");

        // 收益指标
        sb.append("## Return Metrics\n\n");
        sb.append("| Metric | Value |\n");
        sb.append("|--------|-------|\n");
        sb.append("| Total Return | ").append(formatPct(r.totalReturn())).append(" |\n");
        sb.append("| Annualized Return | ").append(formatPct(r.annualizedReturn())).append(" |\n");
        sb.append("| Max Drawdown | ").append(formatPct(r.maxDrawdown())).append(" |\n");
        sb.append("\n");

        // 风险指标
        sb.append("## Risk Metrics\n\n");
        sb.append("| Metric | Value |\n");
        sb.append("|--------|-------|\n");
        sb.append("| Sharpe Ratio | ").append(r.sharpeRatio()).append(" |\n");
        sb.append("| Sortino Ratio | ").append(r.sortinoRatio()).append(" |\n");
        sb.append("| Calmar Ratio | ").append(r.calmarRatio()).append(" |\n");
        sb.append("\n");

        // 交易统计
        sb.append("## Trade Statistics\n\n");
        sb.append("| Metric | Value |\n");
        sb.append("|--------|-------|\n");
        sb.append("| Total Trades | ").append(r.totalTrades()).append(" |\n");
        sb.append("| Winning Trades | ").append(r.winningTrades()).append(" |\n");
        sb.append("| Losing Trades | ").append(r.losingTrades()).append(" |\n");
        sb.append("| Win Rate | ").append(formatPct(r.winRate())).append(" |\n");
        sb.append("| Avg Win | ").append(r.avgWin()).append(" |\n");
        sb.append("| Avg Loss | ").append(r.avgLoss()).append(" |\n");
        sb.append("| Profit Factor | ").append(r.profitFactor()).append(" |\n");
        sb.append("| Max Consecutive Losses | ").append(r.maxConsecutiveLosses()).append(" |\n");
        sb.append("| Max Win Streak | ").append(r.maxWinStreak()).append(" |\n");
        sb.append("| Max Lose Streak | ").append(r.maxLoseStreak()).append(" |\n");
        sb.append("| Avg Trade Duration | ").append(formatDuration(r.avgTradeDuration())).append(" |\n");
        sb.append("\n");

        // 月度收益
        if (r.monthlyReturns() != null && !r.monthlyReturns().isEmpty()) {
            sb.append("## Monthly Returns\n\n");
            sb.append("| Month | Return |\n");
            sb.append("|-------|--------|\n");
            for (Map.Entry<String, BigDecimal> entry : r.monthlyReturns().entrySet()) {
                sb.append("| ").append(entry.getKey()).append(" | ").append(entry.getValue()).append(" |\n");
            }
            sb.append("\n");
        }

        // 交易明细（最多显示前 50 条）
        List<Trade> trades = result.trades();
        if (!trades.isEmpty()) {
            sb.append("## Trade Details").append(trades.size() > 50 ? " (showing first 50)" : "").append("\n\n");
            sb.append("| # | Side | Entry | Exit | Quantity | PnL | Entry Time | Exit Time |\n");
            sb.append("|---|------|-------|------|----------|-----|------------|----------|\n");
            int limit = Math.min(trades.size(), 50);
            for (int i = 0; i < limit; i++) {
                Trade t = trades.get(i);
                sb.append("| ").append(i + 1)
                        .append(" | ").append(t.side())
                        .append(" | ").append(t.entryPrice())
                        .append(" | ").append(t.exitPrice())
                        .append(" | ").append(t.quantity())
                        .append(" | ").append(t.realizedPnL())
                        .append(" | ").append(DATE_FMT.format(Instant.ofEpochMilli(t.entryTime())))
                        .append(" | ").append(DATE_FMT.format(Instant.ofEpochMilli(t.exitTime())))
                        .append(" |\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 导出交易记录为 CSV 格式。
     *
     * @param trades 交易记录列表
     * @return CSV 文本
     */
    public String exportCsv(List<Trade> trades) {
        StringBuilder sb = new StringBuilder();
        sb.append("instrument,side,quantity,entryPrice,exitPrice,entryTime,exitTime,realizedPnL\n");

        for (Trade t : trades) {
            sb.append(t.instrument().symbol()).append(",")
                    .append(t.side()).append(",")
                    .append(t.quantity()).append(",")
                    .append(t.entryPrice()).append(",")
                    .append(t.exitPrice()).append(",")
                    .append(t.entryTime()).append(",")
                    .append(t.exitTime()).append(",")
                    .append(t.realizedPnL()).append("\n");
        }

        return sb.toString();
    }

    /**
     * 导出为 JSON 格式。
     *
     * @param result 回测结果
     * @return JSON 文本
     */
    public String exportJson(BacktestResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize BacktestResult to JSON", e);
        }
    }

    private String formatPct(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP) + "%";
    }

    private String formatDuration(BigDecimal millis) {
        long ms = millis.longValue();
        if (ms < 1000) {
            return ms + "ms";
        }
        long seconds = ms / 1000;
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        seconds = seconds % 60;
        if (minutes < 60) {
            return minutes + "m " + seconds + "s";
        }
        long hours = minutes / 60;
        minutes = minutes % 60;
        return hours + "h " + minutes + "m " + seconds + "s";
    }
}
