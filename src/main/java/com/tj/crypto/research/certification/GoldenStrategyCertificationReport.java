package com.tj.crypto.research.certification;

import com.tj.crypto.backtest.robustness.CscvPboResult;
import com.tj.crypto.backtest.robustness.DeflatedSharpeResult;
import com.tj.crypto.backtest.robustness.PurgedEmbargoSplit;

import java.math.BigDecimal;
import java.util.List;

/** Auditable local research-gate report. It deliberately cannot assert production Alpha. */
public record GoldenStrategyCertificationReport(
        String reportSchemaVersion,
        CertificationStatus status,
        boolean alphaCertified,
        String statusMeaning,
        long generatedAtEpochMillis,
        String datasetId,
        String datasetManifestSha256,
        String registrationId,
        String registrationSha256,
        boolean datasetIntegrityPassed,
        boolean declaredImmutableRealMarketData,
        GoldenExperimentRegistration.CostAssumptions costAssumptions,
        List<TrialSummary> completeRegisteredTrialFamily,
        List<PurgedEmbargoSplit> purgedEmbargoSplits,
        DeflatedSharpeResult deflatedSharpe,
        CscvPboResult cscvPbo,
        BaselineComparison baselineComparison,
        List<String> failedCriteria,
        List<String> limitations
) {
    public GoldenStrategyCertificationReport {
        if (alphaCertified) {
            throw new IllegalArgumentException("local certification reports may never assert Alpha certification");
        }
        completeRegisteredTrialFamily = List.copyOf(completeRegisteredTrialFamily);
        purgedEmbargoSplits = List.copyOf(purgedEmbargoSplits);
        failedCriteria = List.copyOf(failedCriteria);
        limitations = List.copyOf(limitations);
    }

    public record TrialSummary(
            String trialId,
            GoldenExperimentRegistration.TrialRole role,
            TrialEvaluation.TrialRunStatus runStatus,
            int observationCount,
            BigDecimal cumulativeNetReturn,
            BigDecimal observationSharpeRatio,
            List<String> failureReasons
    ) {
        public TrialSummary {
            failureReasons = List.copyOf(failureReasons);
        }
    }

    public record BaselineComparison(
            String selectedTrialId,
            String buyAndHoldTrialId,
            String bestSimpleRuleTrialId,
            BigDecimal candidateCumulativeNetReturn,
            BigDecimal buyAndHoldCumulativeNetReturn,
            BigDecimal bestSimpleRuleCumulativeNetReturn,
            BigDecimal excessOverBuyAndHold,
            BigDecimal excessOverBestSimpleRule
    ) {}

    public enum CertificationStatus {
        DATASET_REJECTED,
        REGISTRATION_REJECTED,
        TRIAL_FAMILY_REJECTED,
        RESEARCH_THRESHOLDS_REJECTED,
        LOCAL_RESEARCH_GATE_PASSED_FORWARD_VALIDATION_REQUIRED
    }
}
