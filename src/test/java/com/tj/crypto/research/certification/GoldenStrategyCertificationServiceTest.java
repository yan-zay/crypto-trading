package com.tj.crypto.research.certification;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.research.certification.GoldenExperimentRegistration.CertificationThresholds;
import com.tj.crypto.research.certification.GoldenExperimentRegistration.CostAssumptions;
import com.tj.crypto.research.certification.GoldenExperimentRegistration.StrategyType;
import com.tj.crypto.research.certification.GoldenExperimentRegistration.TrialDefinition;
import com.tj.crypto.research.certification.GoldenExperimentRegistration.TrialRole;
import com.tj.crypto.research.dataset.DatasetManifest;
import com.tj.crypto.research.dataset.ImmutableArtifactIO;
import com.tj.crypto.research.dataset.ImmutableBarDatasetVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoldenStrategyCertificationServiceTest {
    @TempDir
    Path directory;

    private final ImmutableArtifactIO artifacts = new ImmutableArtifactIO();
    private final GoldenStrategyCertificationService service = new GoldenStrategyCertificationService();

    @Test
    void refusesToCertifySyntheticFixtureAsAlpha() throws Exception {
        Path manifestPath = dataset(DatasetManifest.DatasetOrigin.TEST_FIXTURE, 17);
        Path registrationPath = registration(manifestPath, validTrials());

        GoldenStrategyCertificationReport report = service.certify(
                manifestPath, registrationPath, 1_786_752_000_000L);

        assertThat(report.status()).isEqualTo(
                GoldenStrategyCertificationReport.CertificationStatus.DATASET_REJECTED);
        assertThat(report.alphaCertified()).isFalse();
        assertThat(report.failedCriteria()).contains("DATASET_NOT_DECLARED_IMMUTABLE_REAL_MARKET_DATA");
        assertThat(report.completeRegisteredTrialFamily()).extracting(
                GoldenStrategyCertificationReport.TrialSummary::trialId)
                .containsExactly("golden-sma", "buy-hold", "momentum-baseline");
        assertThat(report.baselineComparison().buyAndHoldTrialId()).isEqualTo("buy-hold");
        assertThat(report.baselineComparison().bestSimpleRuleTrialId()).isEqualTo("momentum-baseline");
        assertThat(report.deflatedSharpe()).isNotNull();
        assertThat(report.cscvPbo()).isNotNull();
        assertThat(report.purgedEmbargoSplits()).hasSize(4);
        assertThat(report.limitations()).anyMatch(text -> text.contains("30-90 day forward"));
    }

    @Test
    void recordsEveryFailedPreregisteredTrialAndFailsClosed() throws Exception {
        Path manifestPath = dataset(DatasetManifest.DatasetOrigin.REAL_MARKET, 17);
        List<TrialDefinition> trials = List.of(
                trial("golden-sma", TrialRole.GOLDEN_CANDIDATE, StrategyType.SMA_CROSS_LONG_ONLY,
                        Map.of("fastWindow", 5, "slowWindow", 4)),
                trial("buy-hold", TrialRole.BUY_AND_HOLD_BASELINE, StrategyType.BUY_AND_HOLD, Map.of()),
                trial("momentum-baseline", TrialRole.SIMPLE_RULE_BASELINE, StrategyType.MOMENTUM_LONG_ONLY,
                        Map.of("lookback", 2)));
        Path registrationPath = registration(manifestPath, trials);

        GoldenStrategyCertificationReport report = service.certify(
                manifestPath, registrationPath, 1_786_752_000_000L);

        assertThat(report.status()).isEqualTo(
                GoldenStrategyCertificationReport.CertificationStatus.TRIAL_FAMILY_REJECTED);
        assertThat(report.failedCriteria()).containsExactly("FAILED_REGISTERED_TRIAL:golden-sma");
        assertThat(report.completeRegisteredTrialFamily()).filteredOn(
                        summary -> summary.trialId().equals("golden-sma"))
                .singleElement().satisfies(summary -> {
                    assertThat(summary.runStatus()).isEqualTo(TrialEvaluation.TrialRunStatus.FAILED);
                    assertThat(summary.failureReasons()).isNotEmpty();
                });
        assertThat(report.alphaCertified()).isFalse();
    }

    @Test
    void rejectsIncompletePreregisteredFamily() {
        assertThatThrownBy(() -> new GoldenExperimentRegistration("1", "registration", 1L,
                "dataset", "a".repeat(64), "golden-sma", List.of(
                trial("golden-sma", TrialRole.GOLDEN_CANDIDATE, StrategyType.SMA_CROSS_LONG_ONLY,
                        Map.of("fastWindow", 2, "slowWindow", 3)),
                trial("buy-hold", TrialRole.BUY_AND_HOLD_BASELINE, StrategyType.BUY_AND_HOLD, Map.of())),
                4, 1, 1, 4, costs(), thresholds()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("candidate, buy-and-hold and simple-rule baseline");
    }

    @Test
    void rejectsRegistrationBoundToDifferentManifestBytes() throws Exception {
        Path manifestPath = dataset(DatasetManifest.DatasetOrigin.REAL_MARKET, 17);
        GoldenExperimentRegistration registration = new GoldenExperimentRegistration("1", "registration", 1L,
                "btc-binance-perp-1m", "a".repeat(64), "golden-sma", validTrials(),
                4, 1, 1, 4, costs(), thresholds());
        Path registrationPath = directory.resolve("wrong.registration.json");
        artifacts.writeNew(registrationPath, registration);

        GoldenStrategyCertificationReport report = service.certify(
                manifestPath, registrationPath, 1_786_752_000_000L);

        assertThat(report.status()).isEqualTo(
                GoldenStrategyCertificationReport.CertificationStatus.REGISTRATION_REJECTED);
        assertThat(report.failedCriteria()).contains("REGISTRATION_MANIFEST_CHECKSUM_MISMATCH");
        assertThat(report.alphaCertified()).isFalse();
    }

    private Path dataset(DatasetManifest.DatasetOrigin origin, int rows) throws Exception {
        Path data = directory.resolve("bars-" + origin + ".csv");
        StringBuilder csv = new StringBuilder(ImmutableBarDatasetVerifier.HEADER).append('\n');
        for (int index = 0; index < rows; index++) {
            int price = 100 + index + (index % 4 == 0 ? 3 : 0);
            csv.append(index * 60_000L).append(',').append(price).append(',').append(price + 2)
                    .append(',').append(price - 2).append(',').append(price + 1)
                    .append(",1000,1000000\n");
        }
        Files.writeString(data, csv.toString(), StandardCharsets.UTF_8);
        DatasetManifest manifest = new DatasetManifest("1", "btc-binance-perp-1m",
                "canonical-finalized-bar-csv", "1", data.getFileName().toString(),
                ImmutableArtifactIO.sha256(data), rows, 0, (rows - 1L) * 60_000L,
                new DatasetManifest.InstrumentIdentity(Exchange.BINANCE, MarketType.PERPETUAL,
                        "BTCUSDT", "BTC", "USDT", Timeframe.M1),
                new DatasetManifest.Provenance(origin,
                        origin == DatasetManifest.DatasetOrigin.REAL_MARKET
                                ? "https://data.binance.vision/" : "fixture://unit-test",
                        "snapshot-2026-08-01", 1_786_665_600_000L));
        Path manifestPath = directory.resolve("dataset-" + origin + ".manifest.json");
        artifacts.writeNew(manifestPath, manifest);
        return manifestPath;
    }

    private Path registration(Path manifestPath, List<TrialDefinition> trials) {
        GoldenExperimentRegistration registration = new GoldenExperimentRegistration("1", "registration", 1L,
                "btc-binance-perp-1m", ImmutableArtifactIO.sha256(manifestPath), "golden-sma", trials,
                4, 1, 1, 4, costs(), thresholds());
        Path path = directory.resolve("registration-" + System.nanoTime() + ".json");
        artifacts.writeNew(path, registration);
        return path;
    }

    private List<TrialDefinition> validTrials() {
        return List.of(
                trial("golden-sma", TrialRole.GOLDEN_CANDIDATE, StrategyType.SMA_CROSS_LONG_ONLY,
                        Map.of("fastWindow", 2, "slowWindow", 4)),
                trial("buy-hold", TrialRole.BUY_AND_HOLD_BASELINE, StrategyType.BUY_AND_HOLD, Map.of()),
                trial("momentum-baseline", TrialRole.SIMPLE_RULE_BASELINE, StrategyType.MOMENTUM_LONG_ONLY,
                        Map.of("lookback", 2)));
    }

    private TrialDefinition trial(String id, TrialRole role, StrategyType type, Map<String, Integer> parameters) {
        return new TrialDefinition(id, role, type, "Preregistered hypothesis for " + id, parameters);
    }

    private CostAssumptions costs() {
        return new CostAssumptions(new BigDecimal("4"), new BigDecimal("2"), 250,
                new BigDecimal("1"), new BigDecimal("0.1"), new BigDecimal("0.8"),
                new BigDecimal("1000"), new BigDecimal("10000"), new BigDecimal("0.01"));
    }

    private CertificationThresholds thresholds() {
        return new CertificationThresholds(BigDecimal.ZERO, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
