package com.tj.crypto.research.agent.eval;

import com.tj.crypto.research.agent.eval.AgentSecurityCorpus.SecurityCase;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Content-addressed Agent evaluation report whose digest binds the complete report body. */
public record AgentEvalReport(
        String schemaVersion,
        String hashAlgorithm,
        String reportSha256,
        ReportBody body) {

    public static final String SCHEMA_VERSION = "agent-eval-report-v1";

    public AgentEvalReport {
        if (!SCHEMA_VERSION.equals(schemaVersion) || !AgentEvalHashing.SHA_256.equals(hashAlgorithm)) {
            throw new AgentEvalException("INVALID_EVALUATION_REPORT",
                    "unsupported report schema or hash algorithm");
        }
        if (body == null) {
            throw new AgentEvalException("INVALID_EVALUATION_REPORT", "report body is required");
        }
        reportSha256 = AgentEvalHashing.requireSha256(reportSha256, "reportSha256");
        String calculated = AgentEvalHashing.sha256Canonical(body);
        if (!calculated.equals(reportSha256)) {
            throw new AgentEvalException("REPORT_DIGEST_MISMATCH",
                    "reportSha256 does not match the canonical report body");
        }
    }

    public static AgentEvalReport create(ReportBody body) {
        return new AgentEvalReport(SCHEMA_VERSION, AgentEvalHashing.SHA_256,
                AgentEvalHashing.sha256Canonical(body), body);
    }

    public enum EvaluationStatus {
        PASSED,
        FAILED
    }

    public record Metrics(
            int expectedCaseCount,
            int observedCaseCount,
            int runCount,
            BigDecimal caseCoverageRate,
            BigDecimal securityPassRate,
            BigDecimal reproducibilityRate,
            BigDecimal qualifiedSuccessRate,
            long criticalViolationCount,
            long meanCostMicrousd,
            long p95LatencyMilliseconds) {

        public Metrics {
            if (expectedCaseCount <= 0 || observedCaseCount < 0 || runCount < 0
                    || observedCaseCount > expectedCaseCount || criticalViolationCount < 0
                    || meanCostMicrousd < 0 || p95LatencyMilliseconds < 0) {
                throw new AgentEvalException("INVALID_EVALUATION_METRICS", "invalid metric bounds");
            }
            caseCoverageRate = requireRate(caseCoverageRate, "caseCoverageRate");
            securityPassRate = requireRate(securityPassRate, "securityPassRate");
            reproducibilityRate = requireRate(reproducibilityRate, "reproducibilityRate");
            qualifiedSuccessRate = requireRate(qualifiedSuccessRate, "qualifiedSuccessRate");
        }
    }

    public record BaselineComparison(
            BigDecimal securityPassRateDelta,
            BigDecimal reproducibilityRateDelta,
            BigDecimal qualifiedSuccessRateDelta,
            long incrementalMeanCostMicrousd,
            long incrementalP95LatencyMilliseconds) {

        public BaselineComparison {
            if (securityPassRateDelta == null || reproducibilityRateDelta == null
                    || qualifiedSuccessRateDelta == null) {
                throw new AgentEvalException("INVALID_BASELINE_COMPARISON", "rate deltas are required");
            }
            securityPassRateDelta = securityPassRateDelta.stripTrailingZeros();
            reproducibilityRateDelta = reproducibilityRateDelta.stripTrailingZeros();
            qualifiedSuccessRateDelta = qualifiedSuccessRateDelta.stripTrailingZeros();
        }
    }

    public record Criterion(String code, boolean passed, String actual, String required) {
        public Criterion {
            code = AgentEvalHashing.required(code, "criterion code");
            actual = AgentEvalHashing.required(actual, "criterion actual value");
            required = AgentEvalHashing.required(required, "criterion required value");
        }
    }

    public record ReportBody(
            long evaluatedAtEpochMillis,
            String corpusVersion,
            String corpusSha256,
            List<SecurityCase> corpus,
            AgentEvalThresholds thresholds,
            AgentEvalSubmission agentSubmission,
            AgentEvalSubmission noAgentBaselineSubmission,
            Metrics agentMetrics,
            Metrics noAgentBaselineMetrics,
            BaselineComparison baselineComparison,
            List<Criterion> criteria,
            EvaluationStatus status,
            List<String> limitations) {

        public ReportBody {
            if (evaluatedAtEpochMillis <= 0) {
                throw new AgentEvalException("INVALID_EVALUATION_TIME",
                        "evaluatedAtEpochMillis must be supplied explicitly");
            }
            corpusVersion = AgentEvalHashing.required(corpusVersion, "corpusVersion");
            corpusSha256 = AgentEvalHashing.requireSha256(corpusSha256, "corpusSha256");
            if (corpus == null || thresholds == null || agentSubmission == null
                    || noAgentBaselineSubmission == null || agentMetrics == null
                    || noAgentBaselineMetrics == null || baselineComparison == null
                    || criteria == null || status == null || limitations == null) {
                throw new AgentEvalException("INVALID_EVALUATION_REPORT", "report body fields are required");
            }
            AgentSecurityCorpus.validateComplete(corpus);
            if (!corpusSha256.equals(AgentEvalHashing.sha256Canonical(corpus))) {
                throw new AgentEvalException("CORPUS_DIGEST_MISMATCH",
                        "corpusSha256 does not match the embedded security corpus");
            }
            List<Criterion> sortedCriteria = criteria.stream()
                    .peek(criterion -> {
                        if (criterion == null) {
                            throw new AgentEvalException("INVALID_EVALUATION_REPORT",
                                    "criteria must not contain null");
                        }
                    })
                    .sorted(Comparator.comparing(Criterion::code))
                    .toList();
            if (new HashSet<>(sortedCriteria.stream().map(Criterion::code).toList()).size()
                    != sortedCriteria.size()) {
                throw new AgentEvalException("DUPLICATE_EVALUATION_CRITERION",
                        "criterion codes must be unique");
            }
            criteria = sortedCriteria;
            EvaluationStatus derived = criteria.stream().allMatch(Criterion::passed)
                    ? EvaluationStatus.PASSED : EvaluationStatus.FAILED;
            if (status != derived) {
                throw new AgentEvalException("INCONSISTENT_EVALUATION_STATUS",
                        "report status must be derived from every criterion");
            }
            limitations = limitations.stream()
                    .map(limit -> AgentEvalHashing.required(limit, "limitation"))
                    .distinct()
                    .sorted()
                    .toList();
        }
    }

    private static BigDecimal requireRate(BigDecimal value, String name) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new AgentEvalException("INVALID_EVALUATION_METRICS", name + " must be in [0, 1]");
        }
        return value.stripTrailingZeros();
    }
}
