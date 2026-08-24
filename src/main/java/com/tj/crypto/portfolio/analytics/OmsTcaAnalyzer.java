package com.tj.crypto.portfolio.analytics;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.InstrumentId;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.TradeSide;
import com.tj.crypto.storage.entity.OmsFillDO;
import com.tj.crypto.storage.entity.OmsOrderDO;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only TCA over durable OMS fills and point-in-time marks.
 *
 * <p>The analyzer recomputes arrival slippage and implementation shortfall rather than trusting
 * a prefilled summary. It performs no order write and its output cannot bypass hard risk controls.
 */
public final class OmsTcaAnalyzer {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal BPS = BigDecimal.valueOf(10_000);

    public TcaReport analyze(OmsOrderDO order,
                             List<OmsFillDO> fills,
                             List<TcaMarketMark> marks,
                             List<TcaFeeConversionRate> feeConversionRates,
                             long analysisEndTimeMs,
                             long knowledgeCutoffTimeMs,
                             long maxMarketDataAgeMs,
                             String quoteCurrency,
                             TcaQuantityUnit quantityUnit) {
        throw new IllegalStateException("TCA input snapshot is required; legacy input is NOT_VERIFIED");
    }

    public TcaReport analyze(TcaInputSnapshot inputSnapshot,
                             OmsOrderDO order,
                             List<OmsFillDO> fills,
                             List<TcaMarketMark> marks,
                             List<TcaFeeConversionRate> feeConversionRates,
                             long analysisEndTimeMs,
                             long knowledgeCutoffTimeMs,
                             long maxMarketDataAgeMs,
                             String quoteCurrency,
                             TcaQuantityUnit quantityUnit) {
        Objects.requireNonNull(inputSnapshot, "inputSnapshot");
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(fills, "fills");
        Objects.requireNonNull(marks, "marks");
        Objects.requireNonNull(feeConversionRates, "feeConversionRates");
        Objects.requireNonNull(quantityUnit, "quantityUnit");
        if (quantityUnit == TcaQuantityUnit.CONTRACTS) {
            throw new IllegalArgumentException("CONTRACTS TCA requires a trusted contract multiplier");
        }
        if (order.getOrderId() == null || order.getOrderId().isBlank()) {
            throw new IllegalArgumentException("OMS orderId is required");
        }
        if (order.getQuantity() == null || order.getQuantity().signum() <= 0) {
            throw new IllegalArgumentException("OMS requested quantity must be positive");
        }
        String accountId = required(order.getAccountId(), "OMS accountId");
        String strategyId = required(order.getStrategyId(), "OMS strategyId");
        TradeSide tradeSide = parseTradeSide(order.getTradeSide());
        InstrumentId instrumentId = instrumentId(order);
        long arrivalTime = order.getSubmittedAtMs() != null && order.getSubmittedAtMs() > 0
                ? order.getSubmittedAtMs() : requirePositiveTime(order.getCreatedAtMs(), "createdAtMs");
        if (analysisEndTimeMs < arrivalTime) {
            throw new IllegalArgumentException("analysisEndTimeMs cannot precede order arrival");
        }
        if (knowledgeCutoffTimeMs < analysisEndTimeMs || maxMarketDataAgeMs < 0) {
            throw new IllegalArgumentException("invalid knowledge cutoff or market-data age limit");
        }
        inputSnapshot.requireCompleteFor(analysisEndTimeMs, knowledgeCutoffTimeMs);
        if (order.getCreatedAtMs() != null && order.getCreatedAtMs() > arrivalTime) {
            throw new IllegalArgumentException("OMS createdAtMs cannot follow order arrival");
        }
        String normalizedQuote = required(quoteCurrency, "quoteCurrency").toUpperCase(Locale.ROOT);
        List<TcaFeeConversionRate> normalizedFeeRates = validateFeeRates(
                feeConversionRates, normalizedQuote, knowledgeCutoffTimeMs);

        List<OmsFillDO> orderedFills = fills.stream()
                .peek(fill -> validateFill(order, fill, arrivalTime, analysisEndTimeMs))
                .sorted(Comparator.comparingLong(OmsFillDO::getFillTime)
                        .thenComparing(fill -> fill.getFillId() == null ? "" : fill.getFillId()))
                .toList();
        if (orderedFills.isEmpty()) throw new IllegalArgumentException("at least one OMS fill is required");
        List<TcaMarketMark> orderedMarks = marks.stream()
                .peek(mark -> validateMark(instrumentId, mark, analysisEndTimeMs,
                        knowledgeCutoffTimeMs, quantityUnit))
                .sorted(Comparator.comparingLong(TcaMarketMark::eventTimeMs))
                .toList();
        if (orderedMarks.isEmpty()) throw new IllegalArgumentException("at least one market mark is required");
        requireUniqueFills(orderedFills);
        requireUnambiguousMarks(orderedMarks);
        requireCompleteVolumeWindow(orderedMarks, arrivalTime, analysisEndTimeMs);

        Optional<BigDecimal> markArrivalPrice = orderedMarks.stream()
                .filter(mark -> mark.eventTimeMs() <= arrivalTime)
                .reduce((ignored, latest) -> latest)
                .map(TcaMarketMark::price);
        Optional<BigDecimal> fillArrivalPrice = arrivalPriceFromFills(orderedFills);
        if (markArrivalPrice.isPresent() && fillArrivalPrice.isPresent()
                && markArrivalPrice.get().compareTo(fillArrivalPrice.get()) != 0) {
            throw new IllegalArgumentException("market mark and OMS fills disagree on order arrival price");
        }
        BigDecimal arrivalPrice = markArrivalPrice.or(() -> fillArrivalPrice)
                .orElseThrow(() -> new IllegalArgumentException(
                        "arrival mark or OMS fill arrival price is required"));
        TcaMarketMark arrivalMark = orderedMarks.stream()
                .filter(mark -> mark.eventTimeMs() <= arrivalTime)
                .reduce((ignored, latest) -> latest)
                .orElseThrow(() -> new IllegalArgumentException("an arrival market mark is required"));
        if (arrivalTime - arrivalMark.eventTimeMs() > maxMarketDataAgeMs
                || arrivalMark.observedAtMs() > arrivalTime) {
            throw new IllegalArgumentException("arrival market mark is stale or was not known at arrival");
        }
        TcaMarketMark endMark = orderedMarks.stream()
                .filter(mark -> mark.eventTimeMs() == analysisEndTimeMs)
                .reduce((ignored, latest) -> latest)
                .orElseThrow(() -> new IllegalArgumentException("an exact market mark is required at analysis end"));
        if (analysisEndTimeMs - endMark.eventTimeMs() > maxMarketDataAgeMs) {
            throw new IllegalArgumentException("analysis-end market mark is stale");
        }

        BigDecimal filledQuantity = BigDecimal.ZERO;
        BigDecimal executedNotional = BigDecimal.ZERO;
        BigDecimal reportedSlippageWeighted = BigDecimal.ZERO;
        BigDecimal feeQuote = BigDecimal.ZERO;
        BigDecimal latencyWeighted = BigDecimal.ZERO;
        for (OmsFillDO fill : orderedFills) {
            BigDecimal quantity = fill.getFillQuantity();
            filledQuantity = filledQuantity.add(quantity, MC);
            executedNotional = executedNotional.add(fill.getFillPrice().multiply(quantity, MC), MC);
            BigDecimal reportedSlippage = fill.getSlippageBps();
            reportedSlippageWeighted = reportedSlippageWeighted.add(
                    reportedSlippage.multiply(quantity, MC), MC);
            feeQuote = feeQuote.add(convertFee(fill, normalizedQuote, normalizedFeeRates,
                    maxMarketDataAgeMs), MC);
            long latency = fill.getFillTime() - arrivalTime;
            latencyWeighted = latencyWeighted.add(quantity.multiply(BigDecimal.valueOf(latency), MC), MC);
        }
        if (filledQuantity.compareTo(order.getQuantity()) > 0) {
            throw new IllegalArgumentException("OMS fills exceed requested order quantity");
        }
        if (order.getFilledQuantity() == null
                || order.getFilledQuantity().compareTo(filledQuantity) != 0) {
            throw new IllegalArgumentException("OMS order filledQuantity disagrees with durable fills");
        }
        BigDecimal marketVolume = orderedMarks.stream()
                .filter(mark -> mark.eventTimeMs() > arrivalTime && mark.eventTimeMs() <= analysisEndTimeMs)
                .map(TcaMarketMark::intervalMarketVolumeQuantity)
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));
        if (marketVolume.signum() <= 0) {
            throw new IllegalArgumentException("positive interval market volume is required for participation");
        }
        if (filledQuantity.compareTo(marketVolume) > 0) {
            throw new IllegalArgumentException("filled quantity exceeds scoped interval market volume");
        }

        BigDecimal direction = BigDecimal.valueOf(tradeSide == TradeSide.BUY ? 1 : -1);
        BigDecimal filledArrivalNotional = filledQuantity.multiply(arrivalPrice, MC);
        BigDecimal arrivalSlippage = executedNotional.subtract(filledArrivalNotional, MC)
                .multiply(direction, MC);
        BigDecimal unfilled = order.getQuantity().subtract(filledQuantity, MC);
        BigDecimal opportunityCost = endMark.price().subtract(arrivalPrice, MC)
                .multiply(unfilled, MC).multiply(direction, MC);
        BigDecimal implementationShortfall = arrivalSlippage.add(feeQuote, MC)
                .add(opportunityCost, MC);
        BigDecimal requestedArrivalNotional = order.getQuantity().multiply(arrivalPrice, MC);
        BigDecimal vwap = executedNotional.divide(filledQuantity, MC);
        if (order.getAvgFillPrice() == null || order.getAvgFillPrice().compareTo(vwap) != 0) {
            throw new IllegalArgumentException("OMS order avgFillPrice disagrees with durable fills");
        }
        boolean fullyFilled = filledQuantity.compareTo(order.getQuantity()) == 0;
        validateOrderCompletion(order, fullyFilled, orderedFills.get(orderedFills.size() - 1).getFillTime());
        BigDecimal arrivalSlippageBps = arrivalSlippage.divide(filledArrivalNotional, MC)
                .multiply(BPS, MC);
        BigDecimal reportedSlippageBps = reportedSlippageWeighted.divide(filledQuantity, MC);
        BigDecimal shortfallBps = implementationShortfall.divide(requestedArrivalNotional, MC)
                .multiply(BPS, MC);
        BigDecimal fillRatio = filledQuantity.divide(order.getQuantity(), MC);
        BigDecimal participation = filledQuantity.divide(marketVolume, MC);
        long firstLatency = orderedFills.get(0).getFillTime() - arrivalTime;
        long lastObservedFillLatency = orderedFills.get(orderedFills.size() - 1).getFillTime() - arrivalTime;
        Long completionLatency = fullyFilled ? lastObservedFillLatency : null;
        long weightedLatency = latencyWeighted.divide(filledQuantity, 0, RoundingMode.HALF_EVEN).longValueExact();

        return new TcaReport(order.getOrderId(), accountId, strategyId,
                instrumentId, tradeSide, order.getQuantity(), filledQuantity, fillRatio,
                arrivalPrice, vwap, endMark.price(), arrivalSlippage, arrivalSlippageBps,
                reportedSlippageBps, feeQuote, opportunityCost, implementationShortfall,
                shortfallBps, firstLatency, weightedLatency, lastObservedFillLatency,
                completionLatency, marketVolume, participation, orderedFills.size(),
                arrivalMark.eventTimeMs(), analysisEndTimeMs, knowledgeCutoffTimeMs,
                inputSnapshot.snapshotId(), inputSnapshot.contentSha256(),
                inputSnapshot.omsManifestSha256(), inputSnapshot.marketManifestSha256(),
                inputSnapshot.fxManifestSha256(), inputSnapshot.omsWatermarkTimeMs(),
                inputSnapshot.marketWatermarkTimeMs(), inputSnapshot.fxWatermarkTimeMs(),
                normalizedQuote, quantityUnit, inputSnapshot.completeness(),
                AnalyticsUse.RESEARCH_ONLY);
    }

    private Optional<BigDecimal> arrivalPriceFromFills(List<OmsFillDO> fills) {
        BigDecimal arrivalPrice = null;
        for (OmsFillDO fill : fills) {
            if (fill.getArrivalPrice() == null || fill.getArrivalPrice().signum() <= 0) continue;
            if (arrivalPrice != null && arrivalPrice.compareTo(fill.getArrivalPrice()) != 0) {
                throw new IllegalArgumentException("OMS fills disagree on order arrival price");
            }
            arrivalPrice = fill.getArrivalPrice();
        }
        return Optional.ofNullable(arrivalPrice);
    }

    private BigDecimal convertFee(OmsFillDO fill, String quoteCurrency,
                                  List<TcaFeeConversionRate> feeToQuoteRates,
                                  long maxMarketDataAgeMs) {
        BigDecimal fee = fill.getFee();
        String currency = required(fill.getFeeCurrency(), "feeCurrency").toUpperCase(Locale.ROOT);
        if (currency.equals(quoteCurrency)) return fee;
        TcaFeeConversionRate rate = feeToQuoteRates.stream()
                .filter(candidate -> candidate.feeCurrency().equals(currency)
                        && candidate.eventTimeMs() <= fill.getFillTime()
                        && candidate.observedAtMs() <= fill.getFillTime())
                .max(Comparator.comparingLong(TcaFeeConversionRate::eventTimeMs))
                .orElseThrow(() -> new IllegalArgumentException(
                        "missing point-in-time fee-to-quote rate for " + currency));
        if (fill.getFillTime() - rate.eventTimeMs() > maxMarketDataAgeMs) {
            throw new IllegalArgumentException("fee-to-quote rate is stale for " + currency);
        }
        return fee.multiply(rate.quotePerFeeCurrency(), MC);
    }

    private List<TcaFeeConversionRate> validateFeeRates(List<TcaFeeConversionRate> feeToQuoteRates,
                                                        String quoteCurrency,
                                                        long knowledgeCutoffTimeMs) {
        Set<String> uniqueFacts = new HashSet<>();
        for (TcaFeeConversionRate rate : feeToQuoteRates) {
            Objects.requireNonNull(rate, "fee conversion rate");
            if (!rate.quoteCurrency().equals(quoteCurrency)) {
                throw new IllegalArgumentException("fee conversion quote currency mismatch");
            }
            if (rate.observedAtMs() > knowledgeCutoffTimeMs) {
                throw new IllegalArgumentException("fee conversion rate was observed after knowledge cutoff");
            }
            String key = rate.feeCurrency() + ':' + rate.eventTimeMs();
            if (!uniqueFacts.add(key)) {
                throw new IllegalArgumentException("duplicate fee conversion fact: " + key);
            }
        }
        return List.copyOf(feeToQuoteRates);
    }

    private void validateFill(OmsOrderDO order, OmsFillDO fill,
                              long arrivalTime, long analysisEndTimeMs) {
        Objects.requireNonNull(fill, "OMS fill");
        if (fill.getFillId() == null || fill.getFillId().isBlank()) {
            throw new IllegalArgumentException("OMS fillId is required");
        }
        if (!order.getOrderId().equals(fill.getOrderId())) {
            throw new IllegalArgumentException("OMS fill belongs to a different order");
        }
        if (!required(order.getAccountId(), "OMS accountId")
                .equals(required(fill.getAccountId(), "OMS fill accountId"))
                || !required(order.getStrategyId(), "OMS strategyId")
                .equals(required(fill.getStrategyId(), "OMS fill strategyId"))) {
            throw new IllegalArgumentException("OMS fill account/strategy disagrees with order");
        }
        if (fill.getFillPrice() == null || fill.getFillPrice().signum() <= 0
                || fill.getFillQuantity() == null || fill.getFillQuantity().signum() <= 0) {
            throw new IllegalArgumentException("OMS fill price and quantity must be positive");
        }
        if (fill.getFillTime() == null || fill.getFillTime() < arrivalTime
                || fill.getFillTime() > analysisEndTimeMs) {
            throw new IllegalArgumentException("OMS fill time lies outside the analysis window");
        }
        if (fill.getFee() == null || fill.getSlippageBps() == null) {
            throw new IllegalArgumentException("OMS fee and reported slippage facts are required");
        }
        required(fill.getFeeCurrency(), "OMS fill feeCurrency");
        boolean hasEventId = fill.getEventId() != null && !fill.getEventId().isBlank();
        boolean hasExchangeTradeId = fill.getExchangeTradeId() != null
                && !fill.getExchangeTradeId().isBlank();
        if (!hasEventId && !hasExchangeTradeId) {
            throw new IllegalArgumentException("OMS fill requires a stable eventId or exchangeTradeId");
        }
    }

    private void validateMark(InstrumentId instrumentId, TcaMarketMark mark,
                              long analysisEndTimeMs, long knowledgeCutoffTimeMs,
                              TcaQuantityUnit quantityUnit) {
        Objects.requireNonNull(mark, "market mark");
        if (!instrumentId.equals(mark.instrumentId())) {
            throw new IllegalArgumentException("market mark belongs to a different instrument");
        }
        if (mark.eventTimeMs() > analysisEndTimeMs) {
            throw new IllegalArgumentException("future market mark lies beyond analysis end");
        }
        if (mark.observedAtMs() > knowledgeCutoffTimeMs) {
            throw new IllegalArgumentException("market mark was observed after knowledge cutoff");
        }
        if (mark.quantityUnit() != quantityUnit) {
            throw new IllegalArgumentException("market-volume quantity unit disagrees with OMS quantity unit");
        }
    }

    private void requireUniqueFills(List<OmsFillDO> fills) {
        Set<String> fillIds = new HashSet<>();
        Set<String> exchangeTradeIds = new HashSet<>();
        Set<String> eventIds = new HashSet<>();
        for (OmsFillDO fill : fills) {
            String fillId = fill.getFillId().trim();
            if (!fillIds.add(fillId)) {
                throw new IllegalArgumentException("duplicate OMS fillId: " + fill.getFillId());
            }
            if (fill.getExchangeTradeId() != null && !fill.getExchangeTradeId().isBlank()
                    && !exchangeTradeIds.add(fill.getExchangeTradeId().trim())) {
                throw new IllegalArgumentException("duplicate OMS exchangeTradeId: "
                        + fill.getExchangeTradeId());
            }
            if (fill.getEventId() != null && !fill.getEventId().isBlank()
                    && !eventIds.add(fill.getEventId().trim())) {
                throw new IllegalArgumentException("duplicate OMS eventId: " + fill.getEventId());
            }
        }
    }

    private void requireUnambiguousMarks(List<TcaMarketMark> marks) {
        Set<String> sourceIds = new HashSet<>();
        for (TcaMarketMark mark : marks) {
            if (!sourceIds.add(mark.sourceEventId())) {
                throw new IllegalArgumentException("duplicate market sourceEventId: " + mark.sourceEventId());
            }
        }
        for (int i = 1; i < marks.size(); i++) {
            if (marks.get(i - 1).eventTimeMs() == marks.get(i).eventTimeMs()) {
                throw new IllegalArgumentException("market marks must have unique event times");
            }
        }
    }

    private void requireCompleteVolumeWindow(List<TcaMarketMark> marks,
                                             long arrivalTimeMs,
                                             long analysisEndTimeMs) {
        List<TcaMarketMark> volumeMarks = marks.stream()
                .filter(mark -> mark.eventTimeMs() > arrivalTimeMs)
                .toList();
        if (volumeMarks.isEmpty() || volumeMarks.get(0).intervalStartTimeMs() != arrivalTimeMs) {
            throw new IllegalArgumentException("market-volume intervals must start exactly at order arrival");
        }
        long expectedStart = arrivalTimeMs;
        for (TcaMarketMark mark : volumeMarks) {
            if (mark.intervalStartTimeMs() != expectedStart) {
                throw new IllegalArgumentException("market-volume intervals must be contiguous and non-overlapping");
            }
            expectedStart = mark.eventTimeMs();
        }
        if (expectedStart != analysisEndTimeMs) {
            throw new IllegalArgumentException("market-volume intervals must end exactly at analysis end");
        }
    }

    private void validateOrderCompletion(OmsOrderDO order, boolean fullyFilled, long lastFillTimeMs) {
        String status = required(order.getStatus(), "OMS status").toUpperCase(Locale.ROOT);
        if (fullyFilled) {
            if (!"FILLED".equals(status) || order.getFilledAtMs() == null
                    || order.getFilledAtMs() != lastFillTimeMs) {
                throw new IllegalArgumentException("fully filled OMS order status/timestamp is inconsistent");
            }
        } else {
            Set<String> admissible = Set.of("PARTIALLY_FILLED", "CANCEL_REQUESTED",
                    "CANCELLED", "EXPIRED");
            if (!admissible.contains(status) || order.getFilledAtMs() != null) {
                throw new IllegalArgumentException("partially filled OMS order status/timestamp is inconsistent");
            }
        }
    }

    private InstrumentId instrumentId(OmsOrderDO order) {
        Exchange exchange = parseExchange(required(order.getExchange(), "exchange"));
        MarketType marketType = parseMarketType(required(order.getMarketType(), "marketType"));
        return new InstrumentId(exchange, marketType, required(order.getSymbol(), "symbol"));
    }

    private Exchange parseExchange(String value) {
        for (Exchange exchange : Exchange.values()) {
            if (exchange.name().equalsIgnoreCase(value) || exchange.getCode().equalsIgnoreCase(value)) {
                return exchange;
            }
        }
        throw new IllegalArgumentException("unknown OMS exchange: " + value);
    }

    private MarketType parseMarketType(String value) {
        for (MarketType marketType : MarketType.values()) {
            if (marketType.name().equalsIgnoreCase(value) || marketType.getCode().equalsIgnoreCase(value)) {
                return marketType;
            }
        }
        throw new IllegalArgumentException("unknown OMS marketType: " + value);
    }

    private TradeSide parseTradeSide(String value) {
        try {
            return TradeSide.valueOf(required(value, "tradeSide").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("OMS tradeSide must be BUY or SELL", exception);
        }
    }

    private long requirePositiveTime(Long value, String field) {
        if (value == null || value <= 0) throw new IllegalArgumentException(field + " must be positive");
        return value;
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
