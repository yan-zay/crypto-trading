package com.tj.crypto.config;

import com.tj.crypto.marketdata.archive.LiveMarketIngestJournal;
import com.tj.crypto.marketdata.archive.LocalFsyncMarketIngestJournal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class MarketDataArchiveConfiguration {

    @Bean
    public LiveMarketIngestJournal liveMarketIngestJournal(
            @Value("${crypto.marketdata.realtime-journal-root:target/market-journal}") String root) {
        return LocalFsyncMarketIngestJournal.wired(Path.of(root));
    }
}
