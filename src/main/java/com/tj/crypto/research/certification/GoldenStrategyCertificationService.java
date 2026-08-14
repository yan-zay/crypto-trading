package com.tj.crypto.research.certification;

import com.tj.crypto.backtest.robustness.CscvPboAnalyzer;
import com.tj.crypto.backtest.robustness.CscvPboResult;
import com.tj.crypto.backtest.robustness.DeflatedSharpeAnalyzer;
import com.tj.crypto.backtest.robustness.DeflatedSharpeRequest;
import com.tj.crypto.backtest.robustness.DeflatedSharpeResult;
import com.tj.crypto.backtest.robustness.PurgedEmbargoSplit;
import com.tj.crypto.backtest.robustness.PurgedEmbargoSplitGenerator;
import com.tj.crypto.backtest.robustness.RegisteredBacktestTrial;
import com.tj.crypto.research.certification.GoldenExperimentRegistration.TrialDefinition;
import com.tj.crypto.research.certification.GoldenExperimentRegistration.TrialRole;
import com.tj.crypto.research.dataset.ImmutableArtifactIO;
import com.tj.crypto.research.dataset.ImmutableBarDatasetVerifier;
import com.tj.crypto.research.dataset.VerifiedBarDataset;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Joins immutable data, pre-registration, purged/embargo, DSR, CSCV/PBO and fixed baselines. */
public final class GoldenStrategyCertificationService {
    private static final String REPORT_VERSION = "1";
    private final ImmutableBarDatasetVerifier datasetVerifier;
    private final ImmutableArtifactIO artifactIO;
    private final DeterministicGoldenTrialEvaluator trialEvaluator;
    private final PurgedEmbargoSplitGenerator splitGenerator;
    private final DeflatedSharpeAnalyzer deflatedSharpeAnalyzer;
    private final CscvPboAnalyzer cscvPboAnalyzer;

    public GoldenStrategyCertificationService() {
        this(new ImmutableBarDatasetVerifier(), new ImmutableArtifactIO(),
                new DeterministicGoldenTrialEvaluator(), new PurgedEmbargoSplitGenerator(),
                new DeflatedSharpeAnalyzer(), new CscvPboAnalyzer());
    }

    GoldenStrategyCertificationService(
            ImmutableBarDatasetVerifier datasetVerifier,
            ImmutableArtifactIO artifactIO,
            DeterministicGoldenTrialEvaluator trialEvaluator,
            PurgedEmbargoSplitGenerator splitGenerator,
            DeflatedSharpeAnalyzer deflatedSharpeAnalyzer,
            CscvPboAnalyzer cscvPboAnalyzer) {
        this.datasetVerifier = datasetVerifier;
        this.artifactIO = artifactIO;
        this.trialEvaluator = trialEvaluator;
        this.splitGenerator = splitGenerator;
        this.deflatedSharpeAnalyzer = deflatedSharpeAnalyzer;
        this.cscvPboAnalyzer = cscvPboAnalyzer;
    }

    public GoldenStrategyCertificationReport certify(
            Path datasetManifestPath,
            Path registrationPath,
            long generatedAtEpochMillis) {
        if (generatedAtEpochMillis <= 0) throw new IllegalArgumentException("generatedAtEpochMillis must be positive");
        VerifiedBarDataset dataset = datasetVerifier.verify(datasetManifestPath);
        GoldenExperimentRegistration registration = artifactIO.read(
                registrationPath, GoldenExperimentRegistration.class);
        String registrationHash = ImmutableArtifactIO.sha256(registrationPath);
        List<String> failedCriteria = new ArrayList<>();

        if (!dataset.eligibleForRealDataResearchGate()) {
            failedCriteria.add("DATASET_NOT_DECLARED_IMMUTABLE_REAL_MARKET_DATA");
        }
        if (!registration.datasetId().equals(dataset.manifest().datasetId())) {
            failedCriteria.add("REGISTRATION_DATASET_ID_MISMATCH");
        }
        if (!registration.datasetManifestSha256().equals(dataset.manifestSha256())) {
            failedCriteria.add("REGISTRATION_MANIFEST_CHECKSUM_MISMATCH");
        }
        if (registration.registeredAtEpochMillis() > generatedAtEpochMillis) {
            failedCriteria.add("REGISTRATION_TIMESTAMP_AFTER_REPORT");
        }

        boolean registrationBound = failedCriteria.stream().noneMatch(reason -> reason.startsWith("REGISTRATION_"));
        if (!registrationBound) {
            return report(dataset, registration, registrationHash, generatedAtEpochMillis,
                    GoldenStrategyCertificationReport.CertificationStatus.REGISTRATION_REJECTED,
                    List.of(), List.of(), null, null, null, failedCriteria);
        }

        List<TrialEvaluation> evaluations = trialEvaluator.evaluate(
                dataset.rows(), registration.trials(), registration.costAssumptions());
        List<GoldenStrategyCertificationReport.TrialSummary> summaries = summaries(registration, evaluations);
        for (TrialEvaluation evaluation : evaluations) {
            if (evaluation.status() == TrialEvaluation.TrialRunStatus.FAILED) {
                failedCriteria.add("FAILED_REGISTERED_TRIAL:" + evaluation.trialId());
            }
        }

        int observations = Math.toIntExact(dataset.manifest().rowCount() - 1);
        List<PurgedEmbargoSplit> splits = List.of();
        try {
            splits = splitGenerator.generate(observations, registration.purgedFoldCount(),
                    registration.purgeBars(), registration.embargoBars());
        } catch (IllegalArgumentException invalidSplit) {
            failedCriteria.add("PURGED_EMBARGO_CONFIGURATION_INVALID:" + invalidSplit.getMessage());
        }
        if (observations % registration.cscvPartitionCount() != 0) {
            failedCriteria.add("CSCV_OBSERVATIONS_NOT_DIVISIBLE_BY_PREREGISTERED_PARTITIONS");
        }

        boolean allTrialsCompleted = evaluations.stream()
                .allMatch(evaluation -> evaluation.status() == TrialEvaluation.TrialRunStatus.COMPLETED);
        DeflatedSharpeResult dsr = null;
        CscvPboResult pbo = null;
        GoldenStrategyCertificationReport.BaselineComparison comparison = null;
        if (allTrialsCompleted && observations % registration.cscvPartitionCount() == 0) {
            Map<String, TrialMetrics> metrics = metricsByTrial(evaluations);
            dsr = deflatedSharpe(registration, metrics, observations);
            pbo = cscvPbo(registration, evaluations, observations);
            comparison = comparison(registration, metrics);
            applyThresholds(registration, dsr, pbo, comparison, failedCriteria);
        }

        GoldenStrategyCertificationReport.CertificationStatus status;
        if (!dataset.eligibleForRealDataResearchGate()) {
            status = GoldenStrategyCertificationReport.CertificationStatus.DATASET_REJECTED;
        } else if (!allTrialsCompleted) {
            status = GoldenStrategyCertificationReport.CertificationStatus.TRIAL_FAMILY_REJECTED;
        } else if (!failedCriteria.isEmpty()) {
            status = GoldenStrategyCertificationReport.CertificationStatus.RESEARCH_THRESHOLDS_REJECTED;
        } else {
            status = GoldenStrategyCertificationReport.CertificationStatus.LOCAL_RESEARCH_GATE_PASSED_FORWARD_VALIDATION_REQUIRED;
        }
        return report(dataset, registration, registrationHash, generatedAtEpochMillis, status,
                summaries, splits, dsr, pbo, comparison, failedCriteria);
    }

    private GoldenStrategyCertificationReport report(
            VerifiedBarDataset dataset,
            GoldenExperimentRegistration registration,
            String registrationHash,
            long generatedAt,
            GoldenStrategyCertificationReport.CertificationStatus status,
            List<GoldenStrategyCertificationReport.TrialSummary> summaries,
            List<PurgedEmbargoSplit> splits,
            DeflatedSharpeResult dsr,
            CscvPboResult pbo,
            GoldenStrategyCertificationReport.BaselineComparison comparison,
            List<String> failedCriteria) {
        List<String> limitations = List.of(
                "This local report never certifies Alpha or authorizes live trading",
                "A declared REAL_MARKET provenance still requires independent source and license review",
                "Backtest evidence does not replace the required 30-90 day forward paper/shadow observation",
                "Latency is represented by the preregistered turnover penalty; finalized bars cannot reproduce intrabar queues",
                "Capacity uses bar quote volume and a preregistered participation cap; order-book impact remains externally unverified",
                "Backtest/paper/shadow parity and multiple unseen market regimes remain external acceptance gates");
        return new GoldenStrategyCertificationReport(REPORT_VERSION, status, false, meaning(status), generatedAt,
                dataset.manifest().datasetId(), dataset.manifestSha256(), registration.registrationId(), registrationHash,
                true, dataset.eligibleForRealDataResearchGate(), registration.costAssumptions(), summaries, splits,
                dsr, pbo, comparison, List.copyOf(failedCriteria), limitations);
    }

    private String meaning(GoldenStrategyCertificationReport.CertificationStatus status) {
        return switch (status) {
            case DATASET_REJECTED -> "Synthetic/test-fixture provenance cannot enter the golden strategy certification gate";
            case REGISTRATION_REJECTED -> "The immutable pre-registration is not correctly bound to this dataset manifest";
            case TRIAL_FAMILY_REJECTED -> "At least one preregistered trial failed and remains recorded in this report";
            case RESEARCH_THRESHOLDS_REJECTED -> "One or more preregistered local research thresholds failed";
            case LOCAL_RESEARCH_GATE_PASSED_FORWARD_VALIDATION_REQUIRED ->
                    "Local backtest gate passed; independent real-data review and 30-90 day forward validation are still required";
        };
    }

    private List<GoldenStrategyCertificationReport.TrialSummary> summaries(
            GoldenExperimentRegistration registration,
            List<TrialEvaluation> evaluations) {
        Map<String, TrialDefinition> definitions = new LinkedHashMap<>();
        for (TrialDefinition definition : registration.trials()) definitions.put(definition.trialId(), definition);
        List<GoldenStrategyCertificationReport.TrialSummary> summaries = new ArrayList<>();
        for (TrialEvaluation evaluation : evaluations) {
            TrialDefinition definition = definitions.get(evaluation.trialId());
            if (evaluation.status() == TrialEvaluation.TrialRunStatus.FAILED) {
                summaries.add(new GoldenStrategyCertificationReport.TrialSummary(evaluation.trialId(), definition.role(),
                        evaluation.status(), 0, null, null, evaluation.failureReasons()));
            } else {
                TrialMetrics metrics = calculateMetrics(evaluation.chronologicalNetReturns());
                summaries.add(new GoldenStrategyCertificationReport.TrialSummary(evaluation.trialId(), definition.role(),
                        evaluation.status(), evaluation.chronologicalNetReturns().size(), decimal(metrics.cumulativeReturn()),
                        decimal(metrics.sharpe()), List.of()));
            }
        }
        return List.copyOf(summaries);
    }

    private Map<String, TrialMetrics> metricsByTrial(List<TrialEvaluation> evaluations) {
        Map<String, TrialMetrics> metrics = new LinkedHashMap<>();
        for (TrialEvaluation evaluation : evaluations) {
            metrics.put(evaluation.trialId(), calculateMetrics(evaluation.chronologicalNetReturns()));
        }
        return metrics;
    }

    private TrialMetrics calculateMetrics(List<Double> returns) {
        double[] values = returns.stream().mapToDouble(Double::doubleValue).toArray();
        double mean = Arrays.stream(values).average().orElse(0);
        double sumSquared = 0;
        for (double value : values) sumSquared += Math.pow(value - mean, 2);
        double deviation = values.length < 2 ? 0 : Math.sqrt(sumSquared / (values.length - 1D));
        double sharpe = deviation == 0 ? 0 : mean / deviation;
        double skew = 0;
        double kurtosis = 3;
        if (deviation > 0 && values.length >= 3) {
            double third = 0;
            double fourth = 0;
            for (double value : values) {
                double standardized = (value - mean) / deviation;
                third += Math.pow(standardized, 3);
                fourth += Math.pow(standardized, 4);
            }
            skew = third / values.length;
            kurtosis = Math.max(0.0000001D, fourth / values.length);
        }
        double growth = 1;
        for (double value : values) growth *= 1 + value;
        return new TrialMetrics(sharpe, skew, kurtosis, growth - 1);
    }

    private DeflatedSharpeResult deflatedSharpe(
            GoldenExperimentRegistration registration,
            Map<String, TrialMetrics> metrics,
            int observations) {
        List<RegisteredBacktestTrial> trials = registration.trials().stream()
                .map(definition -> new RegisteredBacktestTrial(definition.trialId(), metrics.get(definition.trialId()).sharpe()))
                .toList();
        TrialMetrics selected = metrics.get(registration.selectedTrialId());
        return deflatedSharpeAnalyzer.analyze(new DeflatedSharpeRequest(registration.selectedTrialId(), trials,
                observations, selected.skewness(), selected.pearsonKurtosis()));
    }

    private CscvPboResult cscvPbo(
            GoldenExperimentRegistration registration,
            List<TrialEvaluation> evaluations,
            int observations) {
        double[][] matrix = new double[observations][evaluations.size()];
        for (int trial = 0; trial < evaluations.size(); trial++) {
            List<Double> returns = evaluations.get(trial).chronologicalNetReturns();
            for (int row = 0; row < observations; row++) matrix[row][trial] = returns.get(row);
        }
        return cscvPboAnalyzer.analyze(registration.trials().stream().map(TrialDefinition::trialId).toList(),
                matrix, registration.cscvPartitionCount());
    }

    private GoldenStrategyCertificationReport.BaselineComparison comparison(
            GoldenExperimentRegistration registration,
            Map<String, TrialMetrics> metrics) {
        TrialDefinition selected = registration.trials().stream()
                .filter(trial -> trial.trialId().equals(registration.selectedTrialId())).findFirst().orElseThrow();
        TrialDefinition buyHold = registration.trials().stream()
                .filter(trial -> trial.role() == TrialRole.BUY_AND_HOLD_BASELINE).findFirst().orElseThrow();
        TrialDefinition simple = registration.trials().stream()
                .filter(trial -> trial.role() == TrialRole.SIMPLE_RULE_BASELINE)
                .max((left, right) -> Double.compare(metrics.get(left.trialId()).cumulativeReturn(),
                        metrics.get(right.trialId()).cumulativeReturn())).orElseThrow();
        double candidate = metrics.get(selected.trialId()).cumulativeReturn();
        double buyHoldReturn = metrics.get(buyHold.trialId()).cumulativeReturn();
        double simpleReturn = metrics.get(simple.trialId()).cumulativeReturn();
        return new GoldenStrategyCertificationReport.BaselineComparison(selected.trialId(), buyHold.trialId(),
                simple.trialId(), decimal(candidate), decimal(buyHoldReturn), decimal(simpleReturn),
                decimal(candidate - buyHoldReturn), decimal(candidate - simpleReturn));
    }

    private void applyThresholds(
            GoldenExperimentRegistration registration,
            DeflatedSharpeResult dsr,
            CscvPboResult pbo,
            GoldenStrategyCertificationReport.BaselineComparison comparison,
            List<String> failures) {
        var thresholds = registration.thresholds();
        if (dsr.deflatedSharpeProbability().compareTo(thresholds.minimumDeflatedSharpeProbability()) < 0) {
            failures.add("DEFLATED_SHARPE_BELOW_PREREGISTERED_THRESHOLD");
        }
        if (pbo.probabilityOfBacktestOverfitting().compareTo(thresholds.maximumPbo()) > 0) {
            failures.add("PBO_ABOVE_PREREGISTERED_THRESHOLD");
        }
        if (comparison.candidateCumulativeNetReturn().compareTo(thresholds.minimumCandidateCumulativeReturn()) < 0) {
            failures.add("CANDIDATE_RETURN_BELOW_PREREGISTERED_THRESHOLD");
        }
        if (comparison.excessOverBuyAndHold().compareTo(thresholds.minimumExcessOverBuyAndHold()) < 0) {
            failures.add("CANDIDATE_DID_NOT_BEAT_BUY_AND_HOLD_BASELINE");
        }
        if (comparison.excessOverBestSimpleRule().compareTo(thresholds.minimumExcessOverSimpleRule()) < 0) {
            failures.add("CANDIDATE_DID_NOT_BEAT_SIMPLE_RULE_BASELINE");
        }
    }

    private BigDecimal decimal(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("report statistic must be finite");
        return BigDecimal.valueOf(value).setScale(10, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private record TrialMetrics(double sharpe, double skewness, double pearsonKurtosis, double cumulativeReturn) {}
}
