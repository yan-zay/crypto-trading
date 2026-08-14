package com.tj.crypto.client;

import com.tj.crypto.common.domain.ChannelType;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketSeriesKey;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.config.properties.CoinglassProperties;
import com.tj.crypto.config.properties.MarketUniverseProperties;
import com.tj.crypto.event.MarketEventBus;
import com.tj.crypto.marketdata.backfill.CoinglassHistoricalDataProvider;
import com.tj.crypto.marketdata.connector.ConnectorHealth;
import com.tj.crypto.marketdata.connector.MarketDataConnector;
import com.tj.crypto.marketdata.connector.SubscriptionRequest;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.MarketEvent;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Polls CoinGlass price-history because its public WebSocket exposes liquidation data,
 * not candle channels. Only newly completed candles are published.
 */
@Slf4j
@Component
public class CoinglassKlinePollingConnector implements MarketDataConnector {

    private static final long MIN_POLL_INTERVAL_MS = 10_000L;

    private final CoinglassHistoricalDataProvider provider;
    private final CoinglassProperties properties;
    private final MarketUniverseProperties marketUniverse;
    private final MarketEventBus eventBus;
    private final Set<SubscriptionRequest> subscriptions = new CopyOnWriteArraySet<>();
    private final List<Consumer<MarketEvent>> handlers = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<MarketSeriesKey, Long> lastPublished = new ConcurrentHashMap<>();
    private final AtomicBoolean connected = new AtomicBoolean();
    private final AtomicBoolean polling = new AtomicBoolean();
    private final AtomicLong messagesReceived = new AtomicLong();
    private final AtomicLong lastMessageTimestamp = new AtomicLong();
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "coinglass-kline-poller");
                thread.setDaemon(true);
                return thread;
            });

    public CoinglassKlinePollingConnector(CoinglassHistoricalDataProvider provider,
                                           CoinglassProperties properties,
                                           MarketUniverseProperties marketUniverse,
                                           MarketEventBus eventBus) {
        this.provider = provider;
        this.properties = properties;
        this.marketUniverse = marketUniverse;
        this.eventBus = eventBus;
        registerConfiguredSubscriptions();
    }

    @Override
    public void connect() {
        if (!properties.isKlinePollingEnabled() || connected.get()) return;
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            lastError.set("COINGLASS_API_KEY is not configured");
            log.warn("CoinGlass K-line polling disabled because COINGLASS_API_KEY is missing");
            return;
        }
        long interval = Math.max(MIN_POLL_INTERVAL_MS, properties.getKlinePollingIntervalMs());
        connected.set(true);
        scheduler.scheduleWithFixedDelay(this::pollSafely, 0, interval, TimeUnit.MILLISECONDS);
        log.info("CoinGlass K-line polling started for {} subscriptions every {} ms",
                subscriptions.size(), interval);
    }

    @Override
    public void disconnect() {
        connected.set(false);
        scheduler.shutdownNow();
    }

    @PreDestroy
    public void shutdown() {
        disconnect();
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public void subscribe(SubscriptionRequest request) {
        validate(request);
        subscriptions.add(request);
    }

    @Override
    public void unsubscribe(SubscriptionRequest request) {
        validate(request);
        subscriptions.remove(request);
    }

    @Override
    public ConnectorHealth health() {
        return new ConnectorHealth(connected.get(), lastMessageTimestamp.get(),
                messagesReceived.get(), 0, lastError.get());
    }

    @Override
    public void onEvent(Consumer<MarketEvent> handler) {
        handlers.add(handler);
    }

    void pollOnce() {
        for (SubscriptionRequest request : subscriptions) {
            if (!connected.get()) return;
            Timeframe timeframe = request.timeframe();
            long currentBucket = (System.currentTimeMillis() / timeframe.getMillis())
                    * timeframe.getMillis();
            long to = currentBucket - timeframe.getMillis();
            long from = to - timeframe.getMillis();
            Instrument instrument = Instrument.of(Exchange.COINGLASS,
                    request.marketType(), request.symbol());
            List<BarEvent> bars = provider.loadBars(instrument, timeframe, from, to);
            MarketSeriesKey key = MarketSeriesKey.of(instrument, timeframe);
            long watermark = lastPublished.getOrDefault(key, Long.MIN_VALUE);
            for (BarEvent bar : bars) {
                long timestamp = bar.metadata().exchangeTimestamp();
                if (!bar.closed() || timestamp <= watermark) continue;
                publish(bar);
                watermark = timestamp;
            }
            if (watermark != Long.MIN_VALUE) lastPublished.put(key, watermark);
        }
    }

    private void pollSafely() {
        if (!connected.get() || !polling.compareAndSet(false, true)) return;
        try {
            pollOnce();
            lastError.set(null);
        } catch (RuntimeException e) {
            lastError.set(e.getMessage());
            log.warn("CoinGlass K-line polling failed: {}", e.getMessage());
        } finally {
            polling.set(false);
        }
    }

    private void publish(BarEvent bar) {
        eventBus.publish(bar);
        messagesReceived.incrementAndGet();
        lastMessageTimestamp.set(System.currentTimeMillis());
        for (Consumer<MarketEvent> handler : handlers) {
            try {
                handler.accept(bar);
            } catch (RuntimeException e) {
                log.warn("CoinGlass K-line handler failed: {}", e.getMessage());
            }
        }
    }

    private void registerConfiguredSubscriptions() {
        for (String symbol : properties.getSymbols()) {
            for (MarketType marketType : properties.getMarketTypes()) {
                for (String timeframe : properties.getTimeframes()) {
                    SubscriptionRequest request = new SubscriptionRequest(
                            Exchange.COINGLASS, marketType, ChannelType.KLINE,
                            MarketUniverseProperties.normalizeSymbol(symbol),
                            Timeframe.fromCode(timeframe));
                    validate(request);
                    subscriptions.add(request);
                }
            }
        }
    }

    private void validate(SubscriptionRequest request) {
        if (request.exchange() != Exchange.COINGLASS
                || request.channelType() != ChannelType.KLINE
                || request.timeframe() == null) {
            throw new IllegalArgumentException(
                    "CoinGlass polling connector supports CoinGlass KLINE subscriptions only");
        }
        marketUniverse.validate(request.exchange(), request.marketType(), request.symbol());
    }
}
