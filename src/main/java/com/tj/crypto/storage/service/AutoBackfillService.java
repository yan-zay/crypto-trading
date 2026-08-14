package com.tj.crypto.storage.service;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.config.properties.MarketUniverseProperties;
import com.tj.crypto.marketdata.backfill.HistoricalDataProviderRegistry;
import com.tj.crypto.marketdata.model.BarEvent;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 自动回填服务。
 * <p>
 * 检查数据覆盖率，当覆盖率低于阈值时自动从 Binance REST API 拉取历史数据并持久化。
 */
@Slf4j
@Component
@AllArgsConstructor
public class AutoBackfillService {

    private static final double COVERAGE_THRESHOLD = 95.0;

    private final DataCoverageService dataCoverageService;
    private final HistoricalDataProviderRegistry providerRegistry;
    private final BarEventPersistenceService barEventPersistenceService;
    private final MarketUniverseProperties marketUniverse;

    /**
     * 检查覆盖率并在需要时自动回填。
     * <p>
     * 仅当覆盖率低于 {@value COVERAGE_THRESHOLD}% 时触发回填。
     * 回填范围为缺失的时间间隙段。
     *
     * @param symbol    交易对符号，如 "BTCUSDT"
     * @param timeframe 时间周期代码，如 "1m", "5m"
     * @param daysBack  回溯天数
     * @return 实际回填的 K 线数量，如果无需回填则返回 0
     */
    public int backfillIfNeeded(String symbol, String timeframe, int daysBack) {
        return backfillIfNeeded(Exchange.BINANCE, MarketType.PERPETUAL,
                symbol, timeframe, daysBack);
    }

    /** 按指定交易所和市场回填，当前支持 Binance 与 OKX。 */
    public int backfillIfNeeded(Exchange exchange, MarketType marketType,
                                String symbol, String timeframe, int daysBack) {
        if (daysBack < 1 || daysBack > 3650) {
            throw new IllegalArgumentException("daysBack must be between 1 and 3650");
        }
        marketUniverse.validate(exchange, marketType, symbol);
        Timeframe tf = Timeframe.fromCode(timeframe);
        long currentBucket = (System.currentTimeMillis() / tf.getMillis()) * tf.getMillis();
        long to = currentBucket - tf.getMillis();
        long from = to - Duration.ofDays(daysBack).toMillis() + tf.getMillis();
        Instrument instrument = Instrument.of(exchange, marketType,
                MarketUniverseProperties.normalizeSymbol(symbol));

        CoverageReport report = dataCoverageService.checkCoverage(instrument, timeframe, from, to);

        if (report.coveragePct() >= COVERAGE_THRESHOLD) {
            log.info("Coverage {}% meets threshold for {} {}, skipping backfill",
                    report.coveragePct(), symbol, timeframe);
            return 0;
        }

        log.info("Coverage {}% below threshold {}% for {} {}, starting backfill ({} gaps)",
                report.coveragePct(), COVERAGE_THRESHOLD, symbol, timeframe, report.gaps().size());

        int totalFilled = 0;

        for (CoverageReport.TimeGap gap : report.gaps()) {
            List<BarEvent> bars = providerRegistry.require(exchange)
                    .loadBars(instrument, tf, gap.from(), gap.to());
            if (!bars.isEmpty()) {
                barEventPersistenceService.saveAll(bars);
                totalFilled += bars.size();
                log.info("Backfilled {} bars for gap [{} -> {}]", bars.size(), gap.from(), gap.to());
            }
        }

        log.info("Backfill complete for {} {}: {} bars filled across {} gaps",
                symbol, timeframe, totalFilled, report.gaps().size());

        return totalFilled;
    }
}
