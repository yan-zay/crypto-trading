package com.tj.crypto.research.agent.eval;

import com.tj.crypto.research.agent.eval.AgentSecurityCorpus.ExpectedDisposition;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Immutable, hash-only evidence submitted by an Agent candidate or the no-Agent baseline. */
public record AgentEvalSubmission(Subject subject, List<Run> runs) {

    public AgentEvalSubmission {
        if (subject == null || runs == null) {
            throw new AgentEvalException("INVALID_EVALUATION_SUBMISSION", "subject and runs are required");
        }
        List<Run> sortedRuns = runs.stream()
                .peek(run -> {
                    if (run == null) {
                        throw new AgentEvalException("INVALID_EVALUATION_SUBMISSION", "runs must not contain null");
                    }
                })
                .sorted(Comparator.comparing(Run::caseId).thenComparing(Run::runId))
                .toList();
        List<String> identities = sortedRuns.stream()
                .map(run -> run.caseId() + "\u0000" + run.runId())
                .toList();
        if (new HashSet<>(identities).size() != identities.size()) {
            throw new AgentEvalException("DUPLICATE_EVALUATION_RUN", "caseId and runId pairs must be unique");
        }
        runs = sortedRuns;
    }

    public enum SubjectKind {
        AGENT,
        NO_AGENT_BASELINE
    }

    public record Subject(
            String subjectId,
            SubjectKind kind,
            String implementationVersion,
            String modelVersion,
            String promptSha256,
            String contextSha256,
            String toolPolicySha256) {

        public Subject {
            subjectId = AgentEvalHashing.required(subjectId, "subjectId");
            implementationVersion = AgentEvalHashing.required(implementationVersion, "implementationVersion");
            modelVersion = AgentEvalHashing.required(modelVersion, "modelVersion");
            if (kind == null) {
                throw new AgentEvalException("INVALID_EVALUATION_SUBJECT", "subject kind is required");
            }
            promptSha256 = AgentEvalHashing.requireSha256(promptSha256, "promptSha256");
            contextSha256 = AgentEvalHashing.requireSha256(contextSha256, "contextSha256");
            toolPolicySha256 = AgentEvalHashing.requireSha256(toolPolicySha256, "toolPolicySha256");
            if (kind == SubjectKind.NO_AGENT_BASELINE && !"NO_MODEL".equals(modelVersion)) {
                throw new AgentEvalException("INVALID_NO_AGENT_BASELINE",
                        "no-Agent baseline must declare modelVersion=NO_MODEL");
            }
            if (kind == SubjectKind.AGENT && "NO_MODEL".equals(modelVersion)) {
                throw new AgentEvalException("INVALID_AGENT_SUBJECT", "Agent candidate must identify its model version");
            }
        }
    }

    public record ToolInvocation(
            String toolName,
            String requestSha256,
            String responseSha256,
            boolean authorized,
            boolean readOnly) {

        public ToolInvocation {
            toolName = AgentEvalHashing.required(toolName, "toolName");
            requestSha256 = AgentEvalHashing.requireSha256(requestSha256, "tool requestSha256");
            responseSha256 = AgentEvalHashing.requireSha256(responseSha256, "tool responseSha256");
        }
    }

    /** Ordered, content-addressed event from one immutable model/tool decision trace. */
    public record TraceEvent(long sequence, TraceEventType type, String contentSha256) {
        public TraceEvent {
            if (sequence < 0) {
                throw new AgentEvalException("INVALID_TRACE_SEQUENCE",
                        "trace sequence must be non-negative");
            }
            if (type == null) {
                throw new AgentEvalException("INVALID_TRACE_EVENT", "trace event type is required");
            }
            contentSha256 = AgentEvalHashing.requireSha256(
                    contentSha256, "trace event contentSha256");
        }
    }

    public enum TraceEventType {
        MODEL_INPUT,
        MODEL_OUTPUT,
        PROMPT,
        CONTEXT,
        TOOL_POLICY,
        TOOL_INVOCATION,
        APPROVAL,
        POLICY_DECISION,
        RESULT
    }

    public record Run(
            String caseId,
            String runId,
            ExpectedDisposition observedDisposition,
            List<ToolInvocation> toolInvocations,
            boolean secretExposed,
            boolean untrustedDataAccepted,
            boolean unauthorizedActionAttempted,
            boolean qualifiedTaskSuccess,
            long costMicrousd,
            long latencyMilliseconds,
            String policyDecisionSha256,
            String approvalEvidenceSha256,
            String resultArtifactSha256,
            List<TraceEvent> traceEvents,
            String traceRootSha256) {

        public Run {
            caseId = AgentEvalHashing.required(caseId, "run caseId");
            runId = AgentEvalHashing.required(runId, "runId");
            if (observedDisposition == null || toolInvocations == null) {
                throw new AgentEvalException("INVALID_EVALUATION_RUN",
                        "observedDisposition and toolInvocations are required");
            }
            if (costMicrousd < 0 || latencyMilliseconds < 0) {
                throw new AgentEvalException("INVALID_EVALUATION_METRIC",
                        "costMicrousd and latencyMilliseconds must be non-negative");
            }
            if (toolInvocations.stream().anyMatch(tool -> tool == null)) {
                throw new AgentEvalException("INVALID_EVALUATION_RUN",
                        "toolInvocations must not contain null");
            }
            // Tool order is security-relevant trace evidence and must never be canonicalized away.
            toolInvocations = List.copyOf(toolInvocations);
            policyDecisionSha256 = AgentEvalHashing.requireSha256(
                    policyDecisionSha256, "policyDecisionSha256");
            if (approvalEvidenceSha256 != null) {
                approvalEvidenceSha256 = AgentEvalHashing.requireSha256(
                        approvalEvidenceSha256, "approvalEvidenceSha256");
            }
            resultArtifactSha256 = AgentEvalHashing.requireSha256(
                    resultArtifactSha256, "resultArtifactSha256");
            traceEvents = validateTrace(traceEvents, toolInvocations, policyDecisionSha256,
                    approvalEvidenceSha256, resultArtifactSha256);
            traceRootSha256 = AgentEvalHashing.requireSha256(traceRootSha256, "traceRootSha256");
            String recomputed = computeTraceRoot(traceEvents);
            if (!recomputed.equals(traceRootSha256)) {
                throw new AgentEvalException("TRACE_ROOT_MISMATCH",
                        "traceRootSha256 does not match the ordered canonical trace");
            }
        }

        /** Preferred construction path: derives the trace root instead of trusting a caller claim. */
        public static Run traced(
                String caseId,
                String runId,
                ExpectedDisposition observedDisposition,
                List<ToolInvocation> toolInvocations,
                boolean secretExposed,
                boolean untrustedDataAccepted,
                boolean unauthorizedActionAttempted,
                boolean qualifiedTaskSuccess,
                long costMicrousd,
                long latencyMilliseconds,
                String policyDecisionSha256,
                String approvalEvidenceSha256,
                String resultArtifactSha256,
                List<TraceEvent> traceEvents) {
            return new Run(caseId, runId, observedDisposition, toolInvocations,
                    secretExposed, untrustedDataAccepted, unauthorizedActionAttempted,
                    qualifiedTaskSuccess, costMicrousd, latencyMilliseconds,
                    policyDecisionSha256, approvalEvidenceSha256, resultArtifactSha256,
                    traceEvents, computeTraceRoot(traceEvents));
        }

        /** Recomputed at evaluation time; callers cannot assert reproducibility with a boolean. */
        public String recomputedTraceRootSha256() {
            return computeTraceRoot(traceEvents);
        }

        /** Legacy self-attested trace API is retained only to fail closed. */
        @Deprecated(forRemoval = false)
        public Run(
                String caseId,
                String runId,
                ExpectedDisposition observedDisposition,
                List<ToolInvocation> toolInvocations,
                boolean secretExposed,
                boolean untrustedDataAccepted,
                boolean unauthorizedActionAttempted,
                boolean qualifiedTaskSuccess,
                boolean reproducibilityVerified,
                long costMicrousd,
                long latencyMilliseconds,
                String policyDecisionSha256,
                String approvalEvidenceSha256,
                String resultArtifactSha256) {
            this(caseId, runId, observedDisposition, toolInvocations,
                    secretExposed, untrustedDataAccepted, unauthorizedActionAttempted,
                    qualifiedTaskSuccess, costMicrousd, latencyMilliseconds,
                    policyDecisionSha256, approvalEvidenceSha256, resultArtifactSha256,
                    rejectLegacyTrace(reproducibilityVerified), "0".repeat(64));
        }

        private static List<TraceEvent> validateTrace(
                List<TraceEvent> events,
                List<ToolInvocation> tools,
                String policyDecisionSha256,
                String approvalEvidenceSha256,
                String resultArtifactSha256) {
            if (events == null || events.isEmpty() || events.stream().anyMatch(event -> event == null)) {
                throw new AgentEvalException("MISSING_EVALUATION_TRACE",
                        "a complete ordered trace is required");
            }
            List<TraceEvent> immutable = List.copyOf(events);
            for (int index = 0; index < immutable.size(); index++) {
                if (immutable.get(index).sequence() != index) {
                    throw new AgentEvalException("INVALID_TRACE_SEQUENCE",
                            "trace sequence must be contiguous, strictly increasing, and start at zero");
                }
            }

            Map<TraceEventType, Integer> counts = new EnumMap<>(TraceEventType.class);
            immutable.forEach(event -> counts.merge(event.type(), 1, Integer::sum));
            for (TraceEventType required : List.of(
                    TraceEventType.MODEL_INPUT, TraceEventType.MODEL_OUTPUT,
                    TraceEventType.PROMPT, TraceEventType.CONTEXT, TraceEventType.TOOL_POLICY,
                    TraceEventType.POLICY_DECISION, TraceEventType.RESULT)) {
                if (counts.getOrDefault(required, 0) != 1) {
                    throw new AgentEvalException("INCOMPLETE_EVALUATION_TRACE",
                            "trace requires exactly one " + required + " event");
                }
            }
            int expectedApprovals = approvalEvidenceSha256 == null ? 0 : 1;
            if (counts.getOrDefault(TraceEventType.APPROVAL, 0) != expectedApprovals) {
                throw new AgentEvalException("INCOMPLETE_EVALUATION_TRACE",
                        "trace approval event does not match approval evidence");
            }
            List<TraceEvent> toolEvents = immutable.stream()
                    .filter(event -> event.type() == TraceEventType.TOOL_INVOCATION)
                    .toList();
            if (toolEvents.size() != tools.size()) {
                throw new AgentEvalException("INCOMPLETE_EVALUATION_TRACE",
                        "trace must contain one ordered event for every tool invocation");
            }
            for (int index = 0; index < tools.size(); index++) {
                String toolDigest = AgentEvalHashing.sha256Canonical(tools.get(index));
                if (!toolDigest.equals(toolEvents.get(index).contentSha256())) {
                    throw new AgentEvalException("TRACE_TOOL_MISMATCH",
                            "trace tool event does not match the invocation at the same ordinal");
                }
            }
            requireEventDigest(immutable, TraceEventType.POLICY_DECISION, policyDecisionSha256);
            requireEventDigest(immutable, TraceEventType.RESULT, resultArtifactSha256);
            if (approvalEvidenceSha256 != null) {
                requireEventDigest(immutable, TraceEventType.APPROVAL, approvalEvidenceSha256);
            }
            return immutable;
        }

        private static void requireEventDigest(
                List<TraceEvent> events, TraceEventType type, String expected) {
            String observed = events.stream()
                    .filter(event -> event.type() == type)
                    .findFirst()
                    .orElseThrow()
                    .contentSha256();
            if (!expected.equals(observed)) {
                throw new AgentEvalException("TRACE_FIELD_MISMATCH",
                        "trace event does not bind the run field: " + type);
            }
        }

        private static String computeTraceRoot(List<TraceEvent> events) {
            if (events == null) {
                throw new AgentEvalException("MISSING_EVALUATION_TRACE",
                        "a complete ordered trace is required");
            }
            return AgentEvalHashing.sha256Canonical(
                    new TraceMaterial("agent-eval-trace-v1", List.copyOf(events)));
        }

        private static List<TraceEvent> rejectLegacyTrace(boolean ignored) {
            throw new AgentEvalException("LEGACY_TRACE_NOT_VERIFIED",
                    "self-attested reproducibility is forbidden; supply a content-addressed trace");
        }

        private record TraceMaterial(String schemaVersion, List<TraceEvent> events) {}
    }
}
