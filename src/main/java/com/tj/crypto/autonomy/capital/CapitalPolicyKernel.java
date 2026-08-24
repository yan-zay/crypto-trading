package com.tj.crypto.autonomy.capital;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic, side-effect-free policy kernel for capital allocation and emergency reduction.
 *
 * <p>The clock and pinned trust root are constructor dependencies; neither policy documents nor
 * requests can self-assert verification. All unknown, stale or mismatched inputs deny resolution.
 */
public final class CapitalPolicyKernel {
    private final Clock clock;
    private final CapitalPolicyTrustRoot trustRoot;
    private final ActiveCapitalPolicyResolver activePolicyResolver;

    public CapitalPolicyKernel(Clock clock, CapitalPolicyTrustRoot trustRoot,
                               ActiveCapitalPolicyResolver activePolicyResolver) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.trustRoot = Objects.requireNonNull(trustRoot, "trustRoot");
        this.activePolicyResolver = Objects.requireNonNull(
                activePolicyResolver, "activePolicyResolver");
    }

    public CapitalEnvelopeResolution resolve(CapitalResolutionRequest request) {
        Objects.requireNonNull(request, "request");
        Instant now = clock.instant();
        Optional<SignedCapitalRiskPolicy> resolvedHead;
        try {
            resolvedHead = activePolicyResolver.findActive(
                    request.expectedPortfolioId(), request.expectedPolicyId());
        } catch (RuntimeException unavailable) {
            return unavailable(request, now);
        }
        if (resolvedHead == null || resolvedHead.isEmpty()) {
            return unavailable(request, now);
        }
        SignedCapitalRiskPolicy signedPolicy = resolvedHead.get();
        CapitalRiskPolicy policy = signedPolicy.policy();
        PolicySignatureVerification verification = trustRoot.verify(signedPolicy, now);
        if (verification != PolicySignatureVerification.VERIFIED) {
            return denied(CapitalResolutionCode.UNTRUSTED_POLICY,
                    "policy signature or trust interval is invalid", policy, request, now);
        }
        if (!policy.currentAt(now)) {
            return denied(CapitalResolutionCode.POLICY_NOT_CURRENT,
                    "policy is not effective at the trusted clock instant", policy, request, now);
        }
        if (!policy.portfolioId().equals(request.expectedPortfolioId())) {
            return denied(CapitalResolutionCode.PORTFOLIO_ID_MISMATCH,
                    "active policy portfolio id does not match", policy, request, now);
        }
        if (!policy.policyId().equals(request.expectedPolicyId())) {
            return denied(CapitalResolutionCode.POLICY_ID_MISMATCH,
                    "expected policy id does not match", policy, request, now);
        }
        if (policy.version() != request.expectedPolicyVersion()) {
            return denied(CapitalResolutionCode.POLICY_VERSION_MISMATCH,
                    "expected policy version does not match", policy, request, now);
        }
        if (!policy.sha256().equals(request.expectedPolicySha256())) {
            return denied(CapitalResolutionCode.POLICY_DIGEST_MISMATCH,
                    "expected policy digest does not match", policy, request, now);
        }
        AccountNode account = policy.account(request.scope().accountId());
        AccountCapitalAllocation allocation = policy.allocation(request.scope().accountId());
        if (account == null || allocation == null) {
            return denied(CapitalResolutionCode.ACCOUNT_NOT_ALLOCATED,
                    "account is outside the policy allocation", policy, request, now);
        }
        if (!account.venue().equals(request.scope().venue())) {
            return denied(CapitalResolutionCode.ACCOUNT_VENUE_MISMATCH,
                    "scope venue differs from the account venue", policy, request, now);
        }
        ScopedRiskBudget budget = policy.budget(request.scope());
        if (budget == null) {
            return denied(CapitalResolutionCode.SCOPE_NOT_ALLOCATED,
                    "no exact account/venue/instrument/strategy budget exists", policy, request, now);
        }
        if (!budget.currentAt(now)) {
            return denied(CapitalResolutionCode.RISK_BUDGET_EXPIRED,
                    "risk budget has expired", policy, request, now);
        }
        if (!request.requestedCapital().unit().equals(policy.portfolioCapital().unit())) {
            return denied(CapitalResolutionCode.VALUATION_UNIT_MISMATCH,
                    "requested capital uses a different valuation unit", policy, request, now);
        }
        if (request.requestedCapital().compareTo(allocation.allocatedCapital()) > 0) {
            return denied(CapitalResolutionCode.CAPITAL_ALLOCATION_EXCEEDED,
                    "requested capital exceeds the account allocation", policy, request, now);
        }
        Instant resolutionExpiry = policy.expiresAt().isBefore(budget.expiresAt())
                ? policy.expiresAt() : budget.expiresAt();
        return new CapitalEnvelopeResolution(CapitalResolutionCode.RESOLVED,
                "exact signed capital envelope resolved", policy.portfolioId(),
                policy.policyId(), policy.version(),
                policy.sha256(), request.scope(), now, resolutionExpiry,
                allocation.allocatedCapital(), budget.limits());
    }

    public EmergencyPolicyTransitionDecision verifyEmergencyReduction(
            SignedCapitalRiskPolicy signedCurrent,
            SignedCapitalRiskPolicy signedCandidate) {
        Objects.requireNonNull(signedCurrent, "signedCurrent");
        Objects.requireNonNull(signedCandidate, "signedCandidate");
        Instant now = clock.instant();
        CapitalRiskPolicy current = signedCurrent.policy();
        CapitalRiskPolicy candidate = signedCandidate.policy();
        if (trustRoot.verify(signedCurrent, now) != PolicySignatureVerification.VERIFIED) {
            return transition(EmergencyTransitionCode.UNTRUSTED_CURRENT_POLICY,
                    "current policy is not trusted", current, candidate, now);
        }
        if (trustRoot.verify(signedCandidate, now) != PolicySignatureVerification.VERIFIED) {
            return transition(EmergencyTransitionCode.UNTRUSTED_CANDIDATE_POLICY,
                    "candidate policy is not trusted", current, candidate, now);
        }
        if (!current.currentAt(now)) {
            return transition(EmergencyTransitionCode.CURRENT_POLICY_NOT_ACTIVE,
                    "current policy is not active", current, candidate, now);
        }
        if (!candidate.currentAt(now)) {
            return transition(EmergencyTransitionCode.CANDIDATE_POLICY_NOT_ACTIVE,
                    "candidate policy is not active", current, candidate, now);
        }
        if (candidate.revisionKind() != PolicyRevisionKind.EMERGENCY_REDUCTION) {
            return transition(EmergencyTransitionCode.NOT_EMERGENCY_REDUCTION,
                    "candidate is not marked EMERGENCY_REDUCTION", current, candidate, now);
        }
        if (!current.policyId().equals(candidate.policyId())
                || !current.portfolioId().equals(candidate.portfolioId())) {
            return transition(EmergencyTransitionCode.POLICY_IDENTITY_CHANGED,
                    "policy or portfolio identity changed", current, candidate, now);
        }
        if (current.version() == Long.MAX_VALUE || candidate.version() != current.version() + 1) {
            return transition(EmergencyTransitionCode.VERSION_NOT_CONSECUTIVE,
                    "candidate version must be exactly current version plus one", current, candidate, now);
        }
        if (!current.sha256().equals(candidate.predecessorSha256())) {
            return transition(EmergencyTransitionCode.PREDECESSOR_MISMATCH,
                    "candidate predecessor does not bind the current policy", current, candidate, now);
        }
        if (candidate.issuedAt().isBefore(current.issuedAt())
                || candidate.effectiveAt().isBefore(current.effectiveAt())) {
            return transition(EmergencyTransitionCode.CHRONOLOGY_INVALID,
                    "candidate cannot backdate the current policy", current, candidate, now);
        }
        if (candidate.expiresAt().isAfter(current.expiresAt())) {
            return transition(EmergencyTransitionCode.VALIDITY_EXTENDED,
                    "emergency policy cannot extend policy validity", current, candidate, now);
        }
        if (!current.accounts().equals(candidate.accounts())) {
            return transition(EmergencyTransitionCode.ACCOUNT_TOPOLOGY_CHANGED,
                    "emergency policy cannot change account topology", current, candidate, now);
        }
        if (!current.portfolioCapital().unit().equals(candidate.portfolioCapital().unit())) {
            return transition(EmergencyTransitionCode.VALUATION_UNIT_CHANGED,
                    "emergency policy cannot change valuation unit", current, candidate, now);
        }
        if (candidate.portfolioCapital().compareTo(current.portfolioCapital()) > 0) {
            return transition(EmergencyTransitionCode.PORTFOLIO_CAPITAL_INCREASED,
                    "emergency policy cannot increase portfolio capital", current, candidate, now);
        }

        boolean reduced = candidate.portfolioCapital().compareTo(current.portfolioCapital()) < 0
                || candidate.expiresAt().isBefore(current.expiresAt());
        for (Map.Entry<String, AccountCapitalAllocation> entry
                : candidate.allocationsByAccount().entrySet()) {
            AccountCapitalAllocation prior = current.allocationsByAccount().get(entry.getKey());
            if (prior == null || entry.getValue().allocatedCapital()
                    .compareTo(prior.allocatedCapital()) > 0) {
                return transition(EmergencyTransitionCode.ACCOUNT_ALLOCATION_INCREASED,
                        "emergency policy cannot add or increase an account allocation",
                        current, candidate, now);
            }
            reduced |= entry.getValue().allocatedCapital().compareTo(prior.allocatedCapital()) < 0;
        }

        if (!current.budgetsByScope().keySet().containsAll(candidate.budgetsByScope().keySet())) {
            return transition(EmergencyTransitionCode.RISK_SCOPE_ADDED,
                    "emergency policy cannot add a risk scope", current, candidate, now);
        }
        reduced |= candidate.budgetsByScope().size() < current.budgetsByScope().size();
        for (Map.Entry<RiskScope, ScopedRiskBudget> entry
                : candidate.budgetsByScope().entrySet()) {
            ScopedRiskBudget prior = current.budgetsByScope().get(entry.getKey());
            ScopedRiskBudget next = entry.getValue();
            if (!next.limits().noGreaterThan(prior.limits())) {
                return transition(EmergencyTransitionCode.RISK_LIMIT_INCREASED,
                        "emergency policy cannot increase a risk limit", current, candidate, now);
            }
            if (next.expiresAt().isAfter(prior.expiresAt())) {
                return transition(EmergencyTransitionCode.RISK_BUDGET_VALIDITY_EXTENDED,
                        "emergency policy cannot extend a risk budget", current, candidate, now);
            }
            reduced |= next.limits().strictlyLessThan(prior.limits())
                    || next.expiresAt().isBefore(prior.expiresAt());
        }
        if (!reduced) {
            return transition(EmergencyTransitionCode.NO_RISK_REDUCTION,
                    "emergency revision must make at least one strict reduction",
                    current, candidate, now);
        }
        return transition(EmergencyTransitionCode.ACCEPTED,
                "signed emergency revision only reduces risk", current, candidate, now);
    }

    private CapitalEnvelopeResolution denied(
            CapitalResolutionCode code,
            String message,
            CapitalRiskPolicy policy,
            CapitalResolutionRequest request,
            Instant now) {
        return new CapitalEnvelopeResolution(code, message, policy.portfolioId(),
                policy.policyId(), policy.version(),
                policy.sha256(), request.scope(), now, null, null, null);
    }

    private CapitalEnvelopeResolution unavailable(CapitalResolutionRequest request, Instant now) {
        return new CapitalEnvelopeResolution(CapitalResolutionCode.ACTIVE_POLICY_UNAVAILABLE,
                "durable active policy head is unavailable", request.expectedPortfolioId(),
                request.expectedPolicyId(), request.expectedPolicyVersion(),
                request.expectedPolicySha256(), request.scope(), now, null, null, null);
    }

    private EmergencyPolicyTransitionDecision transition(
            EmergencyTransitionCode code,
            String message,
            CapitalRiskPolicy current,
            CapitalRiskPolicy candidate,
            Instant now) {
        return new EmergencyPolicyTransitionDecision(code, message,
                current.sha256(), candidate.sha256(), now);
    }
}
