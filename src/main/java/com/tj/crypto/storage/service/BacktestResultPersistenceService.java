package com.tj.crypto.storage.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.admin.dto.BacktestEquityPointDTO;
import com.tj.crypto.admin.dto.BacktestResultDTO;
import com.tj.crypto.admin.dto.BacktestSignalDTO;
import com.tj.crypto.admin.dto.BacktestTradeDTO;
import com.tj.crypto.backtest.engine.BacktestResult;
import com.tj.crypto.backtest.engine.BacktestResultListener;
import com.tj.crypto.backtest.engine.BacktestExecutionContext;
import com.tj.crypto.backtest.portfolio.Trade;
import com.tj.crypto.backtest.report.EquityPoint;
import com.tj.crypto.backtest.report.PerformanceReport;
import com.tj.crypto.backtest.robustness.BacktestRobustnessAnalyzer;
import com.tj.crypto.storage.entity.BacktestEquityPointDO;
import com.tj.crypto.storage.entity.BacktestRunDO;
import com.tj.crypto.storage.entity.BacktestSignalDO;
import com.tj.crypto.storage.entity.BacktestTradeDO;
import com.tj.crypto.storage.mapper.BacktestEquityPointMapper;
import com.tj.crypto.storage.mapper.BacktestRunMapper;
import com.tj.crypto.storage.mapper.BacktestSignalMapper;
import com.tj.crypto.storage.mapper.BacktestTradeMapper;
import com.tj.crypto.strategy.core.SignalEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Persists complete research runs and exposes summary/detail read models. */
@Service
public class BacktestResultPersistenceService implements BacktestResultListener {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final BacktestRunMapper runMapper;
    private final BacktestTradeMapper tradeMapper;
    private final BacktestEquityPointMapper equityPointMapper;
    private final BacktestSignalMapper signalMapper;
    private final ObjectMapper objectMapper;
    private final BacktestRobustnessAnalyzer robustnessAnalyzer;
    private final DataLineageService dataLineageService;

    public BacktestResultPersistenceService(BacktestRunMapper runMapper,
                                            BacktestTradeMapper tradeMapper,
                                            BacktestEquityPointMapper equityPointMapper,
                                            BacktestSignalMapper signalMapper,
                                            ObjectMapper objectMapper,
                                            BacktestRobustnessAnalyzer robustnessAnalyzer,
                                            DataLineageService dataLineageService) {
        this.runMapper = runMapper;
        this.tradeMapper = tradeMapper;
        this.equityPointMapper = equityPointMapper;
        this.signalMapper = signalMapper;
        this.objectMapper = objectMapper;
        this.robustnessAnalyzer = robustnessAnalyzer;
        this.dataLineageService = dataLineageService;
    }

    @Override
    @Transactional
    public void onCompleted(BacktestResult result) {
        String requestedId = BacktestExecutionContext.resultId();
        String runId = requestedId == null || requestedId.isBlank()
                ? UUID.randomUUID().toString() : requestedId;
        runMapper.insert(toRun(runId, result));
        int sequence = 0;
        for (Trade trade : result.trades()) {
            tradeMapper.insert(toTrade(runId, sequence++, trade));
        }
        sequence = 0;
        for (EquityPoint point : result.equityCurve()) {
            equityPointMapper.insert(toEquityPoint(runId, sequence++, point));
        }
        sequence = 0;
        for (SignalEvent signal : result.signals()) {
            signalMapper.insert(toSignal(runId, sequence++, signal));
        }
    }

    @Override
    public boolean requiredForCompletion() {
        return true;
    }

    /** Returns light summaries. Trade, signal, and equity details are loaded by find(). */
    public List<BacktestResultDTO> recent(int limit) {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("limit must be between 1 and 500");
        }
        return runMapper.selectRecent(limit).stream()
                .map(run -> toDto(run, false))
                .toList();
    }

    public BacktestResultDTO find(String runId) {
        BacktestRunDO run = runMapper.selectById(runId);
        return run == null ? null : toDto(run, true);
    }

    public Map<String, Object> researchMetadata(String runId) {
        BacktestRunDO run = runMapper.selectById(runId);
        if (run == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", runId);
        result.put("robustness", readObject(run.getRobustnessJson()));
        result.put("reproducibility", readObject(run.getReproducibilityJson()));
        result.put("executionQuality", readObject(run.getExecutionQualityJson()));
        return result;
    }

    private BacktestRunDO toRun(String runId, BacktestResult result) {
        PerformanceReport report = result.performanceReport();
        BacktestRunDO target = new BacktestRunDO();
        target.setRunId(runId);
        target.setStrategyName(result.strategyName());
        target.setStrategyConfigJson(result.strategyConfigJson());
        target.setExchange(result.config().instrument().exchange().getCode());
        target.setMarketType(result.config().instrument().marketType().name());
        target.setSymbol(result.config().instrument().symbol());
        target.setTimeframe(result.config().timeframe().getCode());
        target.setDataStartTime(result.config().dataStartTime());
        target.setStartTime(result.config().startTime());
        target.setEndTime(result.config().endTime());
        target.setInitialCapital(result.config().initialBalance());
        target.setFinalCapital(result.finalBalance());
        target.setTotalReturnPct(report.totalReturn());
        target.setAnnualizedReturnPct(report.annualizedReturn());
        target.setMaxDrawdownPct(report.maxDrawdown());
        target.setWinRatePct(report.winRate());
        target.setSharpeRatio(report.sharpeRatio());
        target.setSortinoRatio(report.sortinoRatio());
        target.setCalmarRatio(report.calmarRatio());
        target.setAvgTradeDurationMs(report.avgTradeDuration());
        target.setTotalTrades(report.totalTrades());
        target.setSignalCount(result.signals().size());
        target.setWinningTrades(report.winningTrades());
        target.setLosingTrades(report.losingTrades());
        target.setMaxWinStreak(report.maxWinStreak());
        target.setMaxLoseStreak(report.maxLoseStreak());
        target.setAvgWin(report.avgWin());
        target.setAvgLoss(report.avgLoss());
        target.setProfitFactor(report.profitFactor());
        target.setTotalFees(report.totalFees());
        target.setMonthlyReturnsJson(writeJson(report.monthlyReturns()));
        target.setAssumptionsJson(report.assumptionsJson());
        long seed = BacktestExecutionContext.randomSeed();
        target.setRobustnessJson(writeJson(robustnessAnalyzer.analyze(result.trades(), seed)));
        target.setReproducibilityJson(writeJson(reproducibility(result, seed)));
        target.setExecutionQualityJson(writeJson(executionQuality(result)));
        return target;
    }

    private Map<String, Object> reproducibility(BacktestResult result, long seed) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("schemaVersion", "backtest-repro-v1");
        values.put("engineVersion", "event-driven-v2");
        values.put("randomSeed", seed);
        values.put("instrument", result.config().instrument().id().value());
        values.put("timeframe", result.config().timeframe().getCode());
        values.put("dataStartTime", result.config().dataStartTime());
        values.put("startTime", result.config().startTime());
        values.put("endTime", result.config().endTime());
        values.put("dataVersion", dataLineageService.getDataVersion(result.config().instrument(),
                result.config().timeframe(), result.config().dataStartTime(), result.config().endTime()));
        values.put("strategyConfigSha256", sha256(result.strategyConfigJson()));
        values.put("assumptionsSha256", sha256(result.performanceReport().assumptionsJson()));
        values.put("javaVersion", System.getProperty("java.version"));
        return values;
    }

    private Map<String, Object> executionQuality(BacktestResult result) {
        BigDecimal turnover = result.trades().stream()
                .map(trade -> trade.entryPrice().add(trade.exitPrice()).multiply(trade.quantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fees = result.trades().stream().map(Trade::totalFee)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal feeBps = turnover.signum() == 0 ? BigDecimal.ZERO
                : fees.multiply(BigDecimal.valueOf(10_000))
                .divide(turnover, 8, RoundingMode.HALF_UP);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("schemaVersion", "execution-quality-v1");
        values.put("fillMode", result.assumptions().fillMode());
        values.put("tradeCount", result.trades().size());
        values.put("turnover", turnover);
        values.put("totalFees", fees);
        values.put("feeBpsOnTurnover", feeBps);
        values.put("limitations", List.of(
                "Historical bars do not reconstruct order-book queue position",
                "Spread and impact assumptions are model outputs, not venue fills"));
        return values;
    }

    private BacktestTradeDO toTrade(String runId, int sequence, Trade trade) {
        BacktestTradeDO target = new BacktestTradeDO();
        target.setRunId(runId);
        target.setSequenceNo(sequence);
        target.setSide(trade.side().name());
        target.setQuantity(trade.quantity());
        target.setEntryPrice(trade.entryPrice());
        target.setExitPrice(trade.exitPrice());
        target.setEntryTime(trade.entryTime());
        target.setExitTime(trade.exitTime());
        target.setRealizedPnl(trade.realizedPnL());
        target.setTotalFee(trade.totalFee());
        target.setNetPnl(trade.netPnL());
        return target;
    }

    private BacktestEquityPointDO toEquityPoint(String runId, int sequence, EquityPoint point) {
        BacktestEquityPointDO target = new BacktestEquityPointDO();
        target.setRunId(runId);
        target.setSequenceNo(sequence);
        target.setEventTime(point.timestamp());
        target.setEquity(point.equity());
        return target;
    }

    private BacktestSignalDO toSignal(String runId, int sequence, SignalEvent signal) {
        BacktestSignalDO target = new BacktestSignalDO();
        target.setRunId(runId);
        target.setSequenceNo(sequence);
        target.setSignalTime(signal.timestamp());
        target.setSignalType(signal.type().name());
        target.setConfidence(signal.confidence());
        String reason = signal.reason();
        target.setReason(reason != null && reason.length() > 1000
                ? reason.substring(0, 1000) : reason);
        target.setFactorSnapshotJson(writeJson(signal.factorSnapshot()));
        return target;
    }

    private BacktestResultDTO toDto(BacktestRunDO run, boolean details) {
        List<BacktestTradeDTO> trades = details ? loadTrades(run.getRunId()) : List.of();
        List<BacktestEquityPointDTO> equity = details
                ? equityPointMapper.selectByRunId(run.getRunId()).stream()
                .map(point -> new BacktestEquityPointDTO(point.getEventTime(), point.getEquity()))
                .toList() : List.of();
        List<BacktestSignalDTO> signals = details ? loadSignals(run.getRunId()) : List.of();
        BigDecimal initial = zero(run.getInitialCapital());
        return new BacktestResultDTO(
                run.getRunId(), run.getStrategyName(), run.getExchange(), run.getMarketType(),
                run.getSymbol(), run.getTimeframe(), formatDate(run.getStartTime()),
                formatDate(run.getEndTime()), initial, zero(run.getFinalCapital()),
                fraction(run.getTotalReturnPct()), fraction(run.getAnnualizedReturnPct()),
                fraction(run.getMaxDrawdownPct()), fraction(run.getWinRatePct()),
                zero(run.getSharpeRatio()), zero(run.getSortinoRatio()),
                zero(run.getCalmarRatio()), zero(run.getAvgTradeDurationMs()),
                integer(run.getTotalTrades()), integer(run.getSignalCount()),
                integer(run.getWinningTrades()), integer(run.getLosingTrades()),
                integer(run.getMaxWinStreak()), integer(run.getMaxLoseStreak()),
                capitalPct(run.getAvgWin(), initial),
                capitalPct(run.getAvgLoss(), initial).negate(),
                zero(run.getProfitFactor()), zero(run.getTotalFees()),
                jsonOrEmpty(run.getStrategyConfigJson()), jsonOrEmpty(run.getAssumptionsJson()),
                monthlyReturns(run.getMonthlyReturnsJson()), equity, signals, trades);
    }

    private List<BacktestTradeDTO> loadTrades(String runId) {
        List<BacktestTradeDTO> trades = new ArrayList<>();
        for (BacktestTradeDO trade : tradeMapper.selectByRunId(runId)) {
            BigDecimal notional = trade.getEntryPrice().multiply(trade.getQuantity());
            BigDecimal pnlPct = notional.signum() == 0 ? BigDecimal.ZERO
                    : trade.getNetPnl().divide(notional, 12, RoundingMode.HALF_UP);
            trades.add(new BacktestTradeDTO(
                    trade.getEntryTime(), trade.getExitTime(), trade.getSide(),
                    trade.getEntryPrice(), trade.getExitPrice(), trade.getQuantity(),
                    trade.getNetPnl(), pnlPct, trade.getTotalFee()));
        }
        return List.copyOf(trades);
    }

    private List<BacktestSignalDTO> loadSignals(String runId) {
        return signalMapper.selectByRunId(runId).stream()
                .map(signal -> new BacktestSignalDTO(
                        signal.getSignalTime(), signal.getSignalType(), signal.getConfidence(),
                        signal.getReason(), readMap(signal.getFactorSnapshotJson())))
                .toList();
    }

    private Map<String, BigDecimal> monthlyReturns(String json) {
        Map<String, BigDecimal> percentages = readMap(json);
        Map<String, BigDecimal> fractions = new LinkedHashMap<>();
        percentages.forEach((month, value) -> fractions.put(month, fraction(value)));
        return Map.copyOf(fractions);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize backtest research data", e);
        }
    }

    private Map<String, BigDecimal> readMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot deserialize persisted backtest JSON", e);
        }
    }

    private Object readObject(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot deserialize research metadata", e);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private BigDecimal fraction(BigDecimal percentage) {
        return zero(percentage).divide(ONE_HUNDRED, 12, RoundingMode.HALF_UP);
    }

    private BigDecimal capitalPct(BigDecimal amount, BigDecimal initial) {
        return amount == null || initial.signum() == 0 ? BigDecimal.ZERO
                : amount.divide(initial, 12, RoundingMode.HALF_UP);
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private int integer(Integer value) {
        return value == null ? 0 : value;
    }

    private String jsonOrEmpty(String value) {
        return value == null || value.isBlank() ? "{}" : value;
    }

    private String formatDate(Long timestamp) {
        return timestamp == null ? "" : Instant.ofEpochMilli(timestamp)
                .atZone(ZoneOffset.UTC).format(DATE);
    }
}
