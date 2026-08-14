package com.tj.crypto.research.dataset;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImmutableBarDatasetVerifierTest {
    @TempDir
    Path directory;

    private final ImmutableArtifactIO artifacts = new ImmutableArtifactIO();
    private final ImmutableBarDatasetVerifier verifier = new ImmutableBarDatasetVerifier();

    @Test
    void verifiesChecksumIdentityRangeOrderingAndContinuity() throws Exception {
        Path data = writeData("bars.csv", 0, 60_000, 120_000);
        Path manifest = writeManifest(data, DatasetManifest.DatasetOrigin.REAL_MARKET, 3, 0, 120_000);

        VerifiedBarDataset verified = verifier.verify(manifest);

        assertThat(verified.rows()).hasSize(3);
        assertThat(verified.rows().get(2).timestamp()).isEqualTo(120_000);
        assertThat(verified.manifest().instrument().exchange()).isEqualTo(Exchange.BINANCE);
        assertThat(verified.eligibleForRealDataResearchGate()).isTrue();
        assertThat(verified.manifestSha256()).hasSize(64);
    }

    @Test
    void rejectsChangedBytesAfterManifestWasCreated() throws Exception {
        Path data = writeData("bars.csv", 0, 60_000, 120_000);
        Path manifest = writeManifest(data, DatasetManifest.DatasetOrigin.REAL_MARKET, 3, 0, 120_000);
        Files.writeString(data, row(0, 100) + row(60_000, 101) + row(120_000, 999),
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> verifier.verify(manifest))
                .isInstanceOfSatisfying(DatasetValidationException.class,
                        error -> assertThat(error.code()).isEqualTo("CHECKSUM_MISMATCH"));
    }

    @Test
    void rejectsGapEvenWhenManifestChecksumMatches() throws Exception {
        Path data = writeData("bars.csv", 0, 60_000, 180_000, 240_000);
        Path manifest = writeManifest(data, DatasetManifest.DatasetOrigin.REAL_MARKET, 5, 0, 240_000);

        assertThatThrownBy(() -> verifier.verify(manifest))
                .isInstanceOfSatisfying(DatasetValidationException.class, error -> {
                    assertThat(error.code()).isEqualTo("TIMEFRAME_GAP");
                    assertThat(error.rowNumber()).isEqualTo(3);
                });
    }

    @Test
    void rejectsDuplicateTimestampBeforeAnyResearchRuns() throws Exception {
        Path data = writeData("bars.csv", 0, 60_000, 60_000);
        Path manifest = writeManifest(data, DatasetManifest.DatasetOrigin.REAL_MARKET, 3, 0, 120_000);

        assertThatThrownBy(() -> verifier.verify(manifest))
                .isInstanceOfSatisfying(DatasetValidationException.class,
                        error -> assertThat(error.code()).isEqualTo("DUPLICATE_TIMESTAMP"));
    }

    private Path writeData(String file, long... timestamps) throws Exception {
        Path path = directory.resolve(file);
        StringBuilder csv = new StringBuilder(ImmutableBarDatasetVerifier.HEADER).append('\n');
        for (int index = 0; index < timestamps.length; index++) csv.append(row(timestamps[index], 100 + index));
        Files.writeString(path, csv.toString(), StandardCharsets.UTF_8);
        return path;
    }

    private String row(long timestamp, int price) {
        return timestamp + "," + price + "," + (price + 2) + "," + (price - 2) + "," + (price + 1)
                + ",10,100000\n";
    }

    private Path writeManifest(Path data, DatasetManifest.DatasetOrigin origin,
                               long count, long from, long to) {
        DatasetManifest manifest = new DatasetManifest("1", "btc-binance-perp-1m", "canonical-finalized-bar-csv",
                "1", data.getFileName().toString(), ImmutableArtifactIO.sha256(data), count, from, to,
                new DatasetManifest.InstrumentIdentity(Exchange.BINANCE, MarketType.PERPETUAL,
                        "BTCUSDT", "BTC", "USDT", Timeframe.M1),
                new DatasetManifest.Provenance(origin, origin == DatasetManifest.DatasetOrigin.REAL_MARKET
                        ? "https://data.binance.vision/" : "fixture://unit-test",
                        "snapshot-2026-08-01", 1_786_665_600_000L));
        Path path = directory.resolve("dataset.manifest.json");
        artifacts.writeNew(path, manifest);
        return path;
    }
}
