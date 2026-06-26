package com.tj.crypto.backtest.data;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.EventMetadata;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV 历史数据提供者。
 * 从 CSV 文件加载历史 Bar 数据。
 *
 * CSV 格式：
 * timestamp,open,high,low,close,volume,quoteVolume
 * 1672515780000,16721.50,16722.00,16721.00,16721.50,100.5,1679231.25
 */
@Slf4j
public class CsvHistoricalDataProvider implements HistoricalDataProvider {

    private final Path csvPath;
    private final Exchange exchange;
    private final MarketType marketType;

    public CsvHistoricalDataProvider(Path csvPath, Exchange exchange, MarketType marketType) {
        this.csvPath = csvPath;
        this.exchange = exchange;
        this.marketType = marketType;
    }

    @Override
    public List<BarEvent> loadBars(Instrument instrument, Timeframe timeframe, long from, long to) {
        List<BarEvent> bars = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(csvPath)) {
            String header = reader.readLine(); // 跳过表头
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    BarEvent bar = parseLine(line, instrument, timeframe);
                    if (bar != null
                            && bar.metadata().exchangeTimestamp() >= from
                            && bar.metadata().exchangeTimestamp() <= to) {
                        bars.add(bar);
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse CSV line: {}", line, e);
                }
            }
        } catch (IOException e) {
            log.error("Failed to read CSV file: {}", csvPath, e);
        }

        log.info("Loaded {} bars from {} for {} {} [{}, {}]",
                bars.size(), csvPath.getFileName(), instrument.symbol(), timeframe.getCode(), from, to);
        return bars;
    }

    private BarEvent parseLine(String line, Instrument instrument, Timeframe timeframe) {
        String[] parts = line.split(",");
        if (parts.length < 7) return null;

        long timestamp = Long.parseLong(parts[0].trim());
        BigDecimal open = new BigDecimal(parts[1].trim());
        BigDecimal high = new BigDecimal(parts[2].trim());
        BigDecimal low = new BigDecimal(parts[3].trim());
        BigDecimal close = new BigDecimal(parts[4].trim());
        BigDecimal volume = new BigDecimal(parts[5].trim());
        BigDecimal quoteVolume = new BigDecimal(parts[6].trim());

        EventMetadata metadata = EventMetadata.of(exchange, timestamp);
        return new BarEvent(instrument, metadata, timeframe,
                open, high, low, close, volume, quoteVolume, true);
    }
}
