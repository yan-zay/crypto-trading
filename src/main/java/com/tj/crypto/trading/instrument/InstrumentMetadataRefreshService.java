package com.tj.crypto.trading.instrument;

import com.tj.crypto.common.domain.Exchange;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fetches current venue rules and versions only semantic changes. */
@Slf4j
@Service
public class InstrumentMetadataRefreshService {
    private final Map<Exchange, InstrumentRuleProvider> providers = new EnumMap<>(Exchange.class);
    private final InstrumentMetadataVersionService versionService;
    @Value("${crypto.instrument-metadata.scheduled-enabled:false}")
    private boolean scheduledEnabled;

    public InstrumentMetadataRefreshService(List<InstrumentRuleProvider> discovered,
                                            InstrumentMetadataVersionService versionService) {
        this.versionService = versionService;
        for (InstrumentRuleProvider provider : discovered) providers.put(provider.exchange(), provider);
    }

    public RefreshReport refresh(Exchange exchange) {
        InstrumentRuleProvider provider = providers.get(exchange);
        if (provider == null) throw new IllegalArgumentException("No metadata provider for " + exchange);
        long observedAt = System.currentTimeMillis();
        int changed = 0;
        List<InstrumentRuleSnapshot> snapshots = provider.fetch();
        for (InstrumentRuleSnapshot snapshot : snapshots) {
            if (versionService.apply(snapshot, observedAt)) changed++;
        }
        return new RefreshReport(exchange, observedAt, snapshots.size(), changed);
    }

    public Map<Exchange, RefreshReport> refreshAll() {
        Map<Exchange, RefreshReport> reports = new LinkedHashMap<>();
        for (Exchange exchange : providers.keySet()) reports.put(exchange, refresh(exchange));
        return reports;
    }

    @Scheduled(cron = "${crypto.instrument-metadata.refresh-cron:0 15 3 * * *}")
    public void scheduledRefresh() {
        if (!scheduledEnabled) return;
        for (Exchange exchange : providers.keySet()) {
            try {
                RefreshReport report = refresh(exchange);
                log.info("Instrument metadata refreshed: {}", report);
            } catch (RuntimeException e) {
                log.warn("Instrument metadata refresh failed for {}: {}", exchange, e.getMessage());
            }
        }
    }

    public record RefreshReport(Exchange exchange, long observedAtMs, int observed, int changed) {}
}
