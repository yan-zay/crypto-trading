package com.tj.crypto.autonomy.execution;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Content-addressed plan decision with zero or one non-dispatchable candidate. */
public final class ExecutionPlan {
    private final ExecutionPlanningCode code;
    private final String planningRequestSha256;
    private final Instant evaluatedAt;
    private final ExecutionCandidate candidate;
    private final String sha256;

    private ExecutionPlan(ExecutionPlanningCode code, String planningRequestSha256,
                          Instant evaluatedAt, ExecutionCandidate candidate) {
        this.code = Objects.requireNonNull(code, "code");
        this.planningRequestSha256 = ExecutionValues.sha256(
                planningRequestSha256, "planningRequestSha256");
        this.evaluatedAt = Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        this.candidate = candidate;
        if ((code == ExecutionPlanningCode.CANDIDATE_CREATED) != (candidate != null)) {
            throw new IllegalArgumentException("only a created plan can contain a candidate");
        }
        StringBuilder canonical = new StringBuilder("execution-plan-v1");
        ExecutionValues.append(canonical, code);
        ExecutionValues.append(canonical, planningRequestSha256);
        ExecutionValues.append(canonical, evaluatedAt);
        ExecutionValues.append(canonical, candidate == null ? null : candidate.sha256());
        ExecutionValues.append(canonical, false);
        this.sha256 = ExecutionValues.digest(canonical.toString());
    }

    static ExecutionPlan denied(ExecutionPlanningCode code, String requestSha256, Instant now) {
        return new ExecutionPlan(code, requestSha256, now, null);
    }

    static ExecutionPlan created(String requestSha256, Instant now, ExecutionCandidate candidate) {
        return new ExecutionPlan(ExecutionPlanningCode.CANDIDATE_CREATED,
                requestSha256, now, candidate);
    }

    public ExecutionPlanningCode code() { return code; }
    public String planningRequestSha256() { return planningRequestSha256; }
    public Instant evaluatedAt() { return evaluatedAt; }
    public Optional<ExecutionCandidate> candidate() { return Optional.ofNullable(candidate); }
    public String sha256() { return sha256; }
    public String planId() { return "execution-plan:sha256:" + sha256; }
    public boolean dispatchAuthorized() { return false; }
}
