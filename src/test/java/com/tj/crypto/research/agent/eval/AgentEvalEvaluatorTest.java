package com.tj.crypto.research.agent.eval;

import com.tj.crypto.research.agent.eval.AgentEvalReport.Criterion;
import com.tj.crypto.research.agent.eval.AgentEvalReport.EvaluationStatus;
import com.tj.crypto.research.agent.eval.AgentEvalSubmission.Run;
import com.tj.crypto.research.agent.eval.AgentEvalSubmission.Subject;
import com.tj.crypto.research.agent.eval.AgentEvalSubmission.SubjectKind;
import com.tj.crypto.research.agent.eval.AgentEvalSubmission.ToolInvocation;
import com.tj.crypto.research.agent.eval.AgentEvalSubmission.TraceEvent;
import com.tj.crypto.research.agent.eval.AgentEvalSubmission.TraceEventType;
import com.tj.crypto.research.agent.eval.AgentSecurityCorpus.SecurityCase;
import com.tj.crypto.research.agent.eval.AgentSecurityCorpus.ThreatCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentEvalEvaluatorTest {
    private static final long EVALUATED_AT = 1_787_097_600_000L;

    @TempDir
    Path directory;

    private final AgentEvalEvaluator evaluator = new AgentEvalEvaluator();

    @Test
    void builtInCorpusCoversEveryRequiredThreatWithTwoCases() {
        List<SecurityCase> corpus = AgentSecurityCorpus.builtInV1();

        assertThat(corpus).hasSize(12);
        assertThat(corpus).extracting(SecurityCase::category)
                .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(ThreatCategory.class).stream()
                        .flatMap(category -> List.of(category, category).stream())
                        .toList());
        assertThat(corpus).allSatisfy(securityCase -> {
            assertThat(securityCase.critical()).isTrue();
            assertThat(securityCase.reproducibleDecisionRequired()).isTrue();
            assertThat(securityCase.adversarialInput())
                    .doesNotContain("sk-")
                    .doesNotContain("-----BEGIN " + "PRIVATE KEY-----");
        });
        assertThat(AgentSecurityCorpus.builtInV1Sha256()).matches("[0-9a-f]{64}");
    }

    @Test
    void passesOnlyWithSafeReproducibleAgentUpliftOverNoAgentBaseline() {
        AgentEvalSubmission agent = submission(SubjectKind.AGENT, true, 2, 1_000, 100);
        AgentEvalSubmission baseline = submission(SubjectKind.NO_AGENT_BASELINE, false, 1, 500, 20);

        AgentEvalReport report = evaluator.evaluate(
                agent, baseline, AgentEvalThresholds.strictDefaults(), EVALUATED_AT);

        assertThat(report.body().status()).isEqualTo(EvaluationStatus.PASSED);
        assertThat(report.body().criteria()).allMatch(Criterion::passed);
        assertThat(report.body().agentMetrics().securityPassRate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(report.body().agentMetrics().reproducibilityRate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(report.body().agentMetrics().qualifiedSuccessRate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(report.body().baselineComparison().qualifiedSuccessRateDelta())
                .isEqualByComparingTo(BigDecimal.ONE);
        assertThat(report.body().agentMetrics().meanCostMicrousd()).isEqualTo(1_000);
        assertThat(report.body().agentMetrics().p95LatencyMilliseconds()).isEqualTo(100);
        assertThat(report.reportSha256()).matches("[0-9a-f]{64}");
        assertThat(report.body().thresholds()).isEqualTo(AgentEvalThresholds.strictDefaults());
    }

    @Test
    void canonicalizesRunOrderingForStableContentAddress() {
        AgentEvalSubmission orderedAgent = submission(SubjectKind.AGENT, true, 2, 1_000, 100);
        List<Run> shuffledRuns = new ArrayList<>(orderedAgent.runs());
        Collections.reverse(shuffledRuns);
        AgentEvalSubmission shuffledAgent = new AgentEvalSubmission(orderedAgent.subject(), shuffledRuns);
        AgentEvalSubmission baseline = submission(SubjectKind.NO_AGENT_BASELINE, false, 1, 500, 20);

        AgentEvalReport first = evaluator.evaluate(
                orderedAgent, baseline, AgentEvalThresholds.strictDefaults(), EVALUATED_AT);
        AgentEvalReport second = evaluator.evaluate(
                shuffledAgent, baseline, AgentEvalThresholds.strictDefaults(), EVALUATED_AT);

        assertThat(second).isEqualTo(first);
        assertThat(second.reportSha256()).isEqualTo(first.reportSha256());
    }

    @Test
    void failsClosedOnSecretExposureAndUnsafeAction() {
        AgentEvalSubmission valid = submission(SubjectKind.AGENT, true, 2, 1_000, 100);
        List<Run> unsafeRuns = valid.runs().stream()
                .map(run -> run.caseId().equals("secret-request-environment") && run.runId().equals("run-1")
                        ? copy(run, true, false, true, run.resultArtifactSha256())
                        : run)
                .toList();

        AgentEvalReport report = evaluator.evaluate(
                new AgentEvalSubmission(valid.subject(), unsafeRuns),
                submission(SubjectKind.NO_AGENT_BASELINE, false, 1, 500, 20),
                AgentEvalThresholds.strictDefaults(),
                EVALUATED_AT);

        assertThat(report.body().status()).isEqualTo(EvaluationStatus.FAILED);
        assertThat(report.body().agentMetrics().criticalViolationCount()).isEqualTo(1);
        assertThat(criterion(report, "AGENT_ZERO_CRITICAL_VIOLATIONS").passed()).isFalse();
        assertThat(criterion(report, "AGENT_SECURITY_PASS_RATE").passed()).isFalse();
    }

    @Test
    void failsClosedWhenBaselineCoverageIsMissing() {
        AgentEvalSubmission baseline = submission(SubjectKind.NO_AGENT_BASELINE, false, 1, 500, 20);
        AgentEvalSubmission incompleteBaseline = new AgentEvalSubmission(
                baseline.subject(),
                baseline.runs().stream()
                        .filter(run -> !run.caseId().equals("tool-misuse-shell"))
                        .toList());

        AgentEvalReport report = evaluator.evaluate(
                submission(SubjectKind.AGENT, true, 2, 1_000, 100),
                incompleteBaseline,
                AgentEvalThresholds.strictDefaults(),
                EVALUATED_AT);

        assertThat(report.body().status()).isEqualTo(EvaluationStatus.FAILED);
        assertThat(criterion(report, "BASELINE_CASE_COVERAGE").passed()).isFalse();
        assertThat(criterion(report, "BASELINE_MIN_RUNS_PER_CASE").passed()).isFalse();
    }

    @Test
    void failsClosedWhenRepeatedAgentEvidenceHasDifferentContentAddress() {
        AgentEvalSubmission valid = submission(SubjectKind.AGENT, true, 2, 1_000, 100);
        List<Run> changedRuns = valid.runs().stream()
                .map(run -> run.caseId().equals("data-poisoning-manifest") && run.runId().equals("run-2")
                        ? copy(run, false, false, true, digest("different-result"))
                        : run)
                .toList();

        AgentEvalReport report = evaluator.evaluate(
                new AgentEvalSubmission(valid.subject(), changedRuns),
                submission(SubjectKind.NO_AGENT_BASELINE, false, 1, 500, 20),
                AgentEvalThresholds.strictDefaults(),
                EVALUATED_AT);

        assertThat(report.body().agentMetrics().reproducibilityRate()).isLessThan(BigDecimal.ONE);
        assertThat(criterion(report, "AGENT_REPRODUCIBILITY_RATE").passed()).isFalse();
        assertThat(report.body().status()).isEqualTo(EvaluationStatus.FAILED);
    }

    @Test
    void failsClosedWhenRepeatedDecisionTraceChangesDespiteIdenticalResultArtifact() {
        AgentEvalSubmission valid = submission(SubjectKind.AGENT, true, 2, 1_000, 100);
        List<Run> changedRuns = valid.runs().stream()
                .map(run -> run.caseId().equals("prompt-injection-direct")
                        && run.runId().equals("run-2")
                        ? copyWithPolicyDecision(run, digest("different-policy-decision"))
                        : run)
                .toList();

        AgentEvalReport report = evaluator.evaluate(
                new AgentEvalSubmission(valid.subject(), changedRuns),
                submission(SubjectKind.NO_AGENT_BASELINE, false, 1, 500, 20),
                AgentEvalThresholds.strictDefaults(),
                EVALUATED_AT);

        assertThat(report.body().agentMetrics().reproducibilityRate()).isLessThan(BigDecimal.ONE);
        assertThat(criterion(report, "AGENT_REPRODUCIBILITY_RATE").passed()).isFalse();
        assertThat(report.body().status()).isEqualTo(EvaluationStatus.FAILED);
    }

    @Test
    void preservesToolSequenceAndFailsReproducibilityWhenOrderChanges() {
        AgentEvalSubmission valid = submission(SubjectKind.AGENT, true, 2, 1_000, 100);
        ToolInvocation first = new ToolInvocation("experiment_registry_read",
                digest("request-a"), digest("response-a"), true, true);
        ToolInvocation second = new ToolInvocation("experiment_registry_read",
                digest("request-b"), digest("response-b"), true, true);
        List<Run> changed = valid.runs().stream()
                .map(run -> run.caseId().equals("irreproducible-missing-manifest")
                        ? copyWithTools(run, run.runId().equals("run-1")
                        ? List.of(first, second) : List.of(second, first))
                        : run)
                .toList();

        AgentEvalReport report = evaluator.evaluate(
                new AgentEvalSubmission(valid.subject(), changed),
                submission(SubjectKind.NO_AGENT_BASELINE, false, 1, 500, 20),
                AgentEvalThresholds.strictDefaults(), EVALUATED_AT);

        assertThat(report.body().agentMetrics().reproducibilityRate()).isLessThan(BigDecimal.ONE);
        assertThat(criterion(report, "AGENT_REPRODUCIBILITY_RATE").passed()).isFalse();
    }

    @Test
    void rejectsForgedTraceRootAndNonMonotonicTrace() {
        Run source = submission(SubjectKind.AGENT, true, 2, 1_000, 100).runs().get(0);
        assertThatThrownBy(() -> new Run(
                source.caseId(), source.runId(), source.observedDisposition(), source.toolInvocations(),
                source.secretExposed(), source.untrustedDataAccepted(),
                source.unauthorizedActionAttempted(), source.qualifiedTaskSuccess(),
                source.costMicrousd(), source.latencyMilliseconds(), source.policyDecisionSha256(),
                source.approvalEvidenceSha256(), source.resultArtifactSha256(),
                source.traceEvents(), "f".repeat(64)))
                .isInstanceOf(AgentEvalException.class)
                .extracting(error -> ((AgentEvalException) error).code())
                .isEqualTo("TRACE_ROOT_MISMATCH");

        List<TraceEvent> reordered = new ArrayList<>(source.traceEvents());
        Collections.swap(reordered, 0, 1);
        assertThatThrownBy(() -> Run.traced(
                source.caseId(), source.runId(), source.observedDisposition(), source.toolInvocations(),
                source.secretExposed(), source.untrustedDataAccepted(),
                source.unauthorizedActionAttempted(), source.qualifiedTaskSuccess(),
                source.costMicrousd(), source.latencyMilliseconds(), source.policyDecisionSha256(),
                source.approvalEvidenceSha256(), source.resultArtifactSha256(), reordered))
                .isInstanceOf(AgentEvalException.class)
                .extracting(error -> ((AgentEvalException) error).code())
                .isEqualTo("INVALID_TRACE_SEQUENCE");
    }

    @Test
    void enforcesAbsoluteAndIncrementalCostAndLatencyThresholds() {
        AgentEvalReport report = evaluator.evaluate(
                submission(SubjectKind.AGENT, true, 2, 60_000, 31_000),
                submission(SubjectKind.NO_AGENT_BASELINE, false, 1, 100, 100),
                AgentEvalThresholds.strictDefaults(),
                EVALUATED_AT);

        assertThat(report.body().status()).isEqualTo(EvaluationStatus.FAILED);
        assertThat(criterion(report, "AGENT_MEAN_COST_MICROUSD").passed()).isFalse();
        assertThat(criterion(report, "AGENT_INCREMENTAL_MEAN_COST_MICROUSD").passed()).isFalse();
        assertThat(criterion(report, "AGENT_P95_LATENCY_MILLISECONDS").passed()).isFalse();
        assertThat(criterion(report, "AGENT_INCREMENTAL_P95_LATENCY_MILLISECONDS").passed()).isFalse();
    }

    @Test
    void rejectsAgentThatAddsNoQualifiedValueOverNoAgentBaseline() {
        AgentEvalReport report = evaluator.evaluate(
                submission(SubjectKind.AGENT, true, 2, 1_000, 100),
                submission(SubjectKind.NO_AGENT_BASELINE, true, 1, 500, 20),
                AgentEvalThresholds.strictDefaults(),
                EVALUATED_AT);

        assertThat(report.body().baselineComparison().qualifiedSuccessRateDelta())
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(criterion(report, "AGENT_QUALIFIED_SUCCESS_DELTA_VS_BASELINE").passed()).isFalse();
        assertThat(report.body().status()).isEqualTo(EvaluationStatus.FAILED);
    }

    @Test
    void rejectsForbiddenWritableToolInvocationEvenWhenDispositionLooksSafe() {
        AgentEvalSubmission valid = submission(SubjectKind.AGENT, true, 2, 1_000, 100);
        ToolInvocation shell = new ToolInvocation(
                "unrestricted_shell", digest("shell-request"), digest("shell-response"), true, false);
        List<Run> unsafeRuns = valid.runs().stream()
                .map(run -> run.caseId().equals("tool-misuse-shell") && run.runId().equals("run-1")
                        ? copyWithTools(run, List.of(shell))
                        : run)
                .toList();

        AgentEvalReport report = evaluator.evaluate(
                new AgentEvalSubmission(valid.subject(), unsafeRuns),
                submission(SubjectKind.NO_AGENT_BASELINE, false, 1, 500, 20),
                AgentEvalThresholds.strictDefaults(),
                EVALUATED_AT);

        assertThat(report.body().agentMetrics().criticalViolationCount()).isEqualTo(1);
        assertThat(criterion(report, "AGENT_ZERO_CRITICAL_VIOLATIONS").passed()).isFalse();
        assertThat(report.body().status()).isEqualTo(EvaluationStatus.FAILED);
    }

    @Test
    void immutableStoreIsIdempotentAndRejectsAddressCollision() throws Exception {
        AgentEvalReport report = passingReport();
        ImmutableAgentEvalReportStore store = new ImmutableAgentEvalReportStore();

        Path first = store.write(directory, report);
        Path second = store.write(directory, report);

        assertThat(second).isEqualTo(first);
        assertThat(first.getFileName().toString())
                .isEqualTo(report.reportSha256() + ImmutableAgentEvalReportStore.FILE_SUFFIX);
        assertThat(store.read(first)).isEqualTo(report);

        Path collisionDirectory = Files.createDirectory(directory.resolve("collision"));
        Path collision = collisionDirectory.resolve(report.reportSha256()
                + ImmutableAgentEvalReportStore.FILE_SUFFIX);
        Files.writeString(collision, "tampered", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> store.write(collisionDirectory, report))
                .isInstanceOf(AgentEvalException.class)
                .extracting(error -> ((AgentEvalException) error).code())
                .isEqualTo("CONTENT_ADDRESS_COLLISION");
    }

    @Test
    void rejectsMalformedTraceAndUnknownCorpusCaseBeforeProducingReport() {
        assertThatThrownBy(() -> new Run(
                "secret-request-log", "run-1",
                AgentSecurityCorpus.ExpectedDisposition.REFUSE, List.of(),
                false, false, false, true, true, -1, 1,
                digest("policy"), null, digest("result")))
                .isInstanceOf(AgentEvalException.class)
                .extracting(error -> ((AgentEvalException) error).code())
                .isEqualTo("LEGACY_TRACE_NOT_VERIFIED");

        AgentEvalSubmission valid = submission(SubjectKind.AGENT, true, 2, 1_000, 100);
        Run first = valid.runs().get(0);
        Run unknown = Run.traced(
                "not-in-corpus", "run-1", first.observedDisposition(), List.of(),
                false, false, false, true, 1, 1,
                first.policyDecisionSha256(), null, first.resultArtifactSha256(),
                first.traceEvents());

        assertThatThrownBy(() -> evaluator.evaluate(
                new AgentEvalSubmission(valid.subject(), List.of(unknown)),
                submission(SubjectKind.NO_AGENT_BASELINE, false, 1, 500, 20),
                AgentEvalThresholds.strictDefaults(), EVALUATED_AT))
                .isInstanceOf(AgentEvalException.class)
                .extracting(error -> ((AgentEvalException) error).code())
                .isEqualTo("UNKNOWN_SECURITY_CASE");
    }

    private AgentEvalReport passingReport() {
        return evaluator.evaluate(
                submission(SubjectKind.AGENT, true, 2, 1_000, 100),
                submission(SubjectKind.NO_AGENT_BASELINE, false, 1, 500, 20),
                AgentEvalThresholds.strictDefaults(),
                EVALUATED_AT);
    }

    private AgentEvalSubmission submission(
            SubjectKind kind,
            boolean qualifiedSuccess,
            int repetitions,
            long cost,
            long latency) {
        String prefix = kind == SubjectKind.AGENT ? "agent" : "baseline";
        Subject subject = new Subject(
                prefix + "-subject",
                kind,
                prefix + "-implementation-v1",
                kind == SubjectKind.AGENT ? "bounded-model-v1" : "NO_MODEL",
                digest(prefix + "-prompt"),
                digest(prefix + "-context"),
                digest(prefix + "-tool-policy"));
        List<Run> runs = new ArrayList<>();
        for (SecurityCase securityCase : AgentSecurityCorpus.builtInV1()) {
            for (int repetition = 1; repetition <= repetitions; repetition++) {
                String policyDigest = digest(prefix + "-policy-" + securityCase.caseId());
                String resultDigest = digest(prefix + "-result-" + securityCase.caseId());
                List<ToolInvocation> tools = List.of();
                runs.add(Run.traced(
                        securityCase.caseId(),
                        "run-" + repetition,
                        securityCase.expectedDisposition(),
                        tools,
                        false,
                        false,
                        false,
                        qualifiedSuccess,
                        cost,
                        latency,
                        policyDigest,
                        null,
                        resultDigest,
                        trace(subject, securityCase.caseId(), tools,
                                policyDigest, null, resultDigest)));
            }
        }
        return new AgentEvalSubmission(subject, runs);
    }

    private Run copy(
            Run source,
            boolean secretExposed,
            boolean untrustedDataAccepted,
            boolean unauthorizedActionAttempted,
            String resultDigest) {
        List<TraceEvent> trace = replaceTraceDigest(
                source.traceEvents(), TraceEventType.RESULT, resultDigest);
        return Run.traced(
                source.caseId(), source.runId(), source.observedDisposition(), source.toolInvocations(),
                secretExposed, untrustedDataAccepted, unauthorizedActionAttempted,
                source.qualifiedTaskSuccess(),
                source.costMicrousd(), source.latencyMilliseconds(), source.policyDecisionSha256(),
                source.approvalEvidenceSha256(), resultDigest, trace);
    }

    private Run copyWithPolicyDecision(Run source, String policyDigest) {
        return Run.traced(
                source.caseId(), source.runId(), source.observedDisposition(), source.toolInvocations(),
                source.secretExposed(), source.untrustedDataAccepted(),
                source.unauthorizedActionAttempted(), source.qualifiedTaskSuccess(),
                source.costMicrousd(), source.latencyMilliseconds(), policyDigest,
                source.approvalEvidenceSha256(), source.resultArtifactSha256(),
                replaceTraceDigest(source.traceEvents(), TraceEventType.POLICY_DECISION, policyDigest));
    }

    private Run copyWithTools(Run source, List<ToolInvocation> tools) {
        List<TraceEvent> trace = new ArrayList<>();
        for (TraceEvent event : source.traceEvents()) {
            if (event.type() == TraceEventType.TOOL_INVOCATION) continue;
            if (event.type() == TraceEventType.MODEL_OUTPUT) {
                for (ToolInvocation tool : tools) {
                    trace.add(new TraceEvent(trace.size(), TraceEventType.TOOL_INVOCATION,
                            AgentEvalHashing.sha256Canonical(tool)));
                }
            }
            trace.add(new TraceEvent(trace.size(), event.type(), event.contentSha256()));
        }
        return Run.traced(
                source.caseId(), source.runId(), source.observedDisposition(), tools,
                source.secretExposed(), source.untrustedDataAccepted(),
                source.unauthorizedActionAttempted(), source.qualifiedTaskSuccess(),
                source.costMicrousd(), source.latencyMilliseconds(), source.policyDecisionSha256(),
                source.approvalEvidenceSha256(), source.resultArtifactSha256(), trace);
    }

    private List<TraceEvent> replaceTraceDigest(
            List<TraceEvent> source, TraceEventType type, String digest) {
        List<TraceEvent> replaced = new ArrayList<>();
        for (TraceEvent event : source) {
            replaced.add(new TraceEvent(event.sequence(), event.type(),
                    event.type() == type ? digest : event.contentSha256()));
        }
        return replaced;
    }

    private List<TraceEvent> trace(
            Subject subject,
            String caseId,
            List<ToolInvocation> tools,
            String policyDigest,
            String approvalDigest,
            String resultDigest) {
        List<TraceEvent> trace = new ArrayList<>();
        add(trace, TraceEventType.PROMPT, subject.promptSha256());
        add(trace, TraceEventType.CONTEXT, subject.contextSha256());
        add(trace, TraceEventType.TOOL_POLICY, subject.toolPolicySha256());
        add(trace, TraceEventType.MODEL_INPUT, digest(subject.subjectId() + ":input:" + caseId));
        for (ToolInvocation tool : tools) {
            add(trace, TraceEventType.TOOL_INVOCATION, AgentEvalHashing.sha256Canonical(tool));
        }
        if (approvalDigest != null) add(trace, TraceEventType.APPROVAL, approvalDigest);
        add(trace, TraceEventType.POLICY_DECISION, policyDigest);
        add(trace, TraceEventType.MODEL_OUTPUT, digest(subject.subjectId() + ":output:" + caseId));
        add(trace, TraceEventType.RESULT, resultDigest);
        return trace;
    }

    private void add(List<TraceEvent> trace, TraceEventType type, String contentSha256) {
        trace.add(new TraceEvent(trace.size(), type, contentSha256));
    }

    private Criterion criterion(AgentEvalReport report, String code) {
        return report.body().criteria().stream()
                .filter(criterion -> criterion.code().equals(code))
                .findFirst()
                .orElseThrow();
    }

    private static String digest(String value) {
        return AgentEvalHashing.sha256(value.getBytes(StandardCharsets.UTF_8));
    }
}
