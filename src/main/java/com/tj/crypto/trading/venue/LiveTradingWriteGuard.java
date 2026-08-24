package com.tj.crypto.trading.venue;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.governance.approval.ApprovalAction;
import com.tj.crypto.governance.approval.DualControlDecision;
import com.tj.crypto.governance.approval.DualControlPolicy;
import com.tj.crypto.governance.approval.DualControlRequest;
import com.tj.crypto.governance.evidence.GovernanceSubject;
import com.tj.crypto.governance.evidence.MediumTermReadinessGate;
import com.tj.crypto.governance.evidence.ReadinessDecision;
import com.tj.crypto.risk.KillSwitch;
import com.tj.crypto.trading.venue.riskreservation.LiveRiskReservationBudget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Fail-closed mode, release, evidence and one-time approval boundary for private venue writes. */
@Component
public class LiveTradingWriteGuard {
    private final PrivateTradingProperties properties;
    private final MediumTermReadinessGate readinessGate;
    private final LiveGovernanceContextProvider contextProvider;
    private final DualControlPolicy dualControlPolicy;
    private final LiveGovernanceDecisionStore decisionStore;
    private final KillSwitch killSwitch;
    private final Clock clock;

    @Autowired
    public LiveTradingWriteGuard(PrivateTradingProperties properties,
                                 MediumTermReadinessGate readinessGate,
                                 LiveGovernanceContextProvider contextProvider,
                                 DualControlPolicy dualControlPolicy,
                                 LiveGovernanceDecisionStore decisionStore,
                                 KillSwitch killSwitch) {
        this(properties, readinessGate, contextProvider, dualControlPolicy,
                decisionStore, killSwitch, Clock.systemUTC());
    }

    LiveTradingWriteGuard(PrivateTradingProperties properties,
                          MediumTermReadinessGate readinessGate,
                          LiveGovernanceContextProvider contextProvider,
                          DualControlPolicy dualControlPolicy,
                          LiveGovernanceDecisionStore decisionStore,
                          Clock clock) {
        this(properties, readinessGate, contextProvider, dualControlPolicy, decisionStore,
                new KillSwitch(), clock);
    }

    LiveTradingWriteGuard(PrivateTradingProperties properties,
                          MediumTermReadinessGate readinessGate,
                          LiveGovernanceContextProvider contextProvider,
                          DualControlPolicy dualControlPolicy,
                          LiveGovernanceDecisionStore decisionStore,
                          KillSwitch killSwitch,
                          Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.readinessGate = readinessGate;
        this.contextProvider = contextProvider;
        this.dualControlPolicy = dualControlPolicy;
        this.decisionStore = decisionStore == null
                ? LiveGovernanceDecisionStore.unavailable() : decisionStore;
        this.killSwitch = Objects.requireNonNull(killSwitch, "killSwitch");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Compatibility construction remains useful for SANDBOX tests, but can never open LIVE risk. */
    public LiveTradingWriteGuard(PrivateTradingProperties properties) {
        this(properties, null, null, null, LiveGovernanceDecisionStore.unavailable(),
                new KillSwitch(), Clock.systemUTC());
    }

    /** A static flag check can never authorize LIVE_CANARY opening risk. */
    public void requireWriteEnabled(Exchange exchange) {
        requireOrderPreflight(exchange);
        if (properties.getOperatingMode() == PrivateTradingProperties.OperatingMode.LIVE_CANARY) {
            throw new LiveAuthorizationException(
                    "LIVE_CANARY risk increases require a governed command authorization");
        }
        requireOpeningFlags(exchange);
    }

    /** Cancellation is an emergency risk-reduction path and never consults opening governance. */
    public void requireCancelEnabled(Exchange exchange) {
        requireSafeMutationEnabled(exchange);
    }

    void requireOrderPreflight(Exchange exchange) {
        properties.validateStartupPolicy();
        if (properties.getOperatingMode() == PrivateTradingProperties.OperatingMode.RESEARCH_ONLY) {
            throw new LiveAuthorizationException("Venue writes are forbidden in RESEARCH_ONLY mode");
        }
        requireSandboxEndpoint(exchange);
        requireVenueEnabled(exchange);
    }

    /** Caller-supplied MARKET references are insufficient for live opening-risk valuation. */
    void requireTrustedMarketValuation(LiveOrderRequest request) {
        Objects.requireNonNull(request, "request");
        if (properties.getOperatingMode() == PrivateTradingProperties.OperatingMode.LIVE_CANARY
                && request.orderType() == com.tj.crypto.execution.model.OrderType.MARKET
                && !request.reduceOnly()) {
            throw new LiveAuthorizationException(
                    "LIVE_CANARY MARKET opening risk requires a trusted server-side valuation snapshot");
        }
    }

    /** Called inside the OMS/risk-reservation transaction so approval consumption is atomic. */
    LiveOrderAuthorization authorizeOrder(VenueOrderCommand command,
                                          LiveRiskReservationBudget budget,
                                          DualControlRequest approval,
                                          String authenticatedOperator) {
        requireBudgetBinding(command, budget);
        requireOrderPreflight(command.instrument().exchange());
        long now = clock.millis();
        String commandSha256 = LiveOrderCommandHasher.hash(command);
        long shortExpiry = Math.addExact(now, properties.getGovernanceAuthorizationTtlMs());

        if (!budget.riskIncreasing()) {
            requireSafeMutationEnabled(command.instrument().exchange());
            return permit(LiveMutationKind.RISK_REDUCTION, commandSha256, now, shortExpiry, null);
        }
        requireOpeningKillSwitch();
        if (properties.getOperatingMode() == PrivateTradingProperties.OperatingMode.SANDBOX) {
            requireOpeningFlags(command.instrument().exchange());
            return permit(LiveMutationKind.SANDBOX_RISK_INCREASE,
                    commandSha256, now, shortExpiry, null);
        }

        requireLiveOpeningStatic(command.instrument().exchange());
        ResolvedLiveGovernanceContext context = requireLiveContext(command, now);
        GovernanceSubject subject = context.subject();
        ReadinessDecision readiness = evaluateReadiness(subject, now);
        requireApprovalBinding(approval, authenticatedOperator, commandSha256, subject.sha256());
        DualControlDecision approvalDecision;
        try {
            approvalDecision = dualControlPolicy.evaluate(approval, now);
        } catch (RuntimeException failure) {
            throw new LiveAuthorizationException("Dual-control verification failed closed");
        }
        if (approvalDecision == null || !approvalDecision.granted()
                || approvalDecision.consumedAtMs() <= 0) {
            throw new LiveAuthorizationException("One-time dual-control approval was not granted");
        }

        long expiresAt = Math.min(shortExpiry,
                Math.min(approval.expiresAtMs(), context.expiresAt().toEpochMilli()));
        if (expiresAt <= now) {
            throw new LiveAuthorizationException("Live authorization has no active dispatch window");
        }
        String evidenceReferences = LiveOrderCommandHasher.evidenceReferences(
                readiness.acceptedEvidence());
        String evidenceSetSha256 = LiveOrderCommandHasher.evidenceDigest(
                readiness.acceptedEvidence());
        LiveGovernanceDecision decision = new LiveGovernanceDecision(
                UUID.randomUUID().toString(), command.clientOrderId(), commandSha256,
                command.instrument().exchange().name(), command.accountId(), command.strategyId(),
                context.assignmentId(), context.assignmentVersion(), context.artifactSha256(),
                subject, evidenceSetSha256, evidenceReferences, approval.requestId(),
                authenticatedOperator, now, expiresAt);
        return permit(LiveMutationKind.LIVE_RISK_INCREASE,
                commandSha256, now, expiresAt, decision);
    }

    LiveOrderAuthorization authorizeCancel(VenueCancelCommand command) {
        requireSafeMutationEnabled(command.instrument().exchange());
        long now = clock.millis();
        return permit(LiveMutationKind.CANCEL, LiveOrderCommandHasher.hash(command), now,
                Math.addExact(now, properties.getGovernanceAuthorizationTtlMs()), null);
    }

    /** Final gateway boundary. It re-reads release/evidence/decision but never consumes approval again. */
    public void revalidateOrder(VenueOrderCommand command, LiveOrderAuthorization authorization) {
        requirePermit(authorization, LiveOrderCommandHasher.hash(command));
        long now = clock.millis();
        switch (authorization.kind()) {
            case RISK_REDUCTION -> {
                if (!command.reduceOnly()) {
                    throw new LiveAuthorizationException("Risk-reduction permit cannot authorize opening risk");
                }
                requireSafeMutationEnabled(command.instrument().exchange());
            }
            case SANDBOX_RISK_INCREASE -> {
                if (command.reduceOnly()
                        || properties.getOperatingMode() != PrivateTradingProperties.OperatingMode.SANDBOX) {
                    throw new LiveAuthorizationException("Sandbox permit does not match the current mutation");
                }
                requireOrderPreflight(command.instrument().exchange());
                requireOpeningKillSwitch();
                requireOpeningFlags(command.instrument().exchange());
            }
            case LIVE_RISK_INCREASE -> revalidateLiveRisk(command, authorization, now);
            case CANCEL -> throw new LiveAuthorizationException("Cancel permit cannot place an order");
        }
    }

    /** Final mutation boundary: validate current policy and atomically consume this permit once. */
    public void consumeOrder(VenueOrderCommand command, LiveOrderAuthorization authorization) {
        revalidateOrder(command, authorization);
        authorization.consumeOnce();
    }

    public void revalidateCancel(VenueCancelCommand command, LiveOrderAuthorization authorization) {
        requirePermit(authorization, LiveOrderCommandHasher.hash(command));
        if (authorization.kind() != LiveMutationKind.CANCEL) {
            throw new LiveAuthorizationException("Order permit cannot authorize cancellation");
        }
        requireSafeMutationEnabled(command.instrument().exchange());
    }

    /** Final mutation boundary: validate current policy and atomically consume this permit once. */
    public void consumeCancel(VenueCancelCommand command, LiveOrderAuthorization authorization) {
        revalidateCancel(command, authorization);
        authorization.consumeOnce();
    }

    public boolean liveWritesEnabled(Exchange exchange) {
        try {
            requireWriteEnabled(exchange);
            return true;
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    private void revalidateLiveRisk(VenueOrderCommand command,
                                    LiveOrderAuthorization authorization, long now) {
        if (command.reduceOnly()
                || properties.getOperatingMode() != PrivateTradingProperties.OperatingMode.LIVE_CANARY) {
            throw new LiveAuthorizationException("LIVE risk permit does not match the current mutation");
        }
        requireOpeningKillSwitch();
        requireLiveOpeningStatic(command.instrument().exchange());
        LiveGovernanceDecision expected = authorization.governanceDecision();
        LiveGovernanceDecision stored;
        try {
            stored = decisionStore.find(expected.decisionId()).orElse(null);
        } catch (RuntimeException failure) {
            throw new LiveAuthorizationException("Governance decision lookup failed closed");
        }
        if (stored == null || !stored.equals(expected)
                || !stored.commandSha256().equals(authorization.commandSha256())) {
            throw new LiveAuthorizationException("Governance decision is missing or inconsistent");
        }
        ResolvedLiveGovernanceContext current = requireLiveContext(command, now);
        if (!current.assignmentId().equals(stored.releaseAssignmentId())
                || current.assignmentVersion() != stored.releaseAssignmentVersion()
                || !current.artifactSha256().equals(stored.strategyArtifactSha256())
                || !current.subject().equals(stored.governanceSubject())) {
            throw new LiveAuthorizationException("Strategy release lineage changed before dispatch");
        }
        ReadinessDecision readiness = evaluateReadiness(current.subject(), now);
        if (!stored.evidenceSetSha256().equals(
                LiveOrderCommandHasher.evidenceDigest(readiness.acceptedEvidence()))) {
            throw new LiveAuthorizationException("Governance evidence set changed before dispatch");
        }
    }

    private ResolvedLiveGovernanceContext requireLiveContext(VenueOrderCommand command, long now) {
        if (contextProvider == null) {
            throw new LiveAuthorizationException("Trusted live release context provider is not configured");
        }
        ResolvedLiveGovernanceContext context = contextProvider.requireCurrentCanary(
                command.strategyId(), now);
        GovernanceSubject subject = context.subject();
        String configuredAccountId = text(configuredAccountId(command.instrument().exchange()),
                "LIVE venue account identity");
        String configuredKeyReference = text(configuredKeyReference(command.instrument().exchange()),
                "LIVE key reference");
        String environment = text(properties.getGovernanceEnvironment(),
                "LIVE governance environment").toUpperCase(Locale.ROOT);
        String expectedAccountScope = command.instrument().exchange().name() + ":" + configuredAccountId;
        if (!command.accountId().equals(configuredAccountId)
                || !subject.accountScope().equals(expectedAccountScope)
                || !subject.environment().equals(environment)
                || !subject.keyReference().equals(configuredKeyReference)
                || !subject.strategyArtifactSha256().equals(context.artifactSha256())) {
            throw new LiveAuthorizationException(
                    "Server release context does not match account, environment, key or artifact");
        }
        return context;
    }

    private ReadinessDecision evaluateReadiness(GovernanceSubject subject, long now) {
        if (readinessGate == null) {
            throw new LiveAuthorizationException("Medium-term readiness gate is not configured");
        }
        ReadinessDecision decision;
        try {
            decision = readinessGate.evaluate(subject, now);
        } catch (RuntimeException failure) {
            throw new LiveAuthorizationException("Governance readiness lookup failed closed");
        }
        if (decision == null || !decision.ready() || decision.acceptedEvidence().isEmpty()) {
            throw new LiveAuthorizationException("Governance evidence is missing, expired or revoked");
        }
        return decision;
    }

    private void requireApprovalBinding(DualControlRequest approval, String authenticatedOperator,
                                        String commandSha256, String subjectSha256) {
        if (approval == null || dualControlPolicy == null) {
            throw new LiveAuthorizationException("One-time dual-control approval is required");
        }
        String operator = text(authenticatedOperator, "authenticated operator");
        if (approval.action() != ApprovalAction.LIVE_RISK_INCREASE
                || !approval.resourceSha256().equals(commandSha256)
                || !approval.governanceSubjectSha256().equals(subjectSha256)
                || !approval.requestedBy().equals(operator)) {
            throw new LiveAuthorizationException(
                    "Approval is bound to a different command, subject, action or requester");
        }
    }

    private void requireBudgetBinding(VenueOrderCommand command, LiveRiskReservationBudget budget) {
        if (command == null || budget == null
                || !command.accountId().equals(budget.accountId())
                || command.instrument().exchange() != budget.exchange()
                || command.instrument().marketType() != budget.marketType()
                || !canonical(command.instrument().symbol()).equals(canonical(budget.symbol()))
                || command.quantity().compareTo(budget.originalQuantity()) != 0
                || budget.riskIncreasing() == command.reduceOnly()) {
            throw new LiveAuthorizationException("Validated risk budget does not bind the exact command");
        }
    }

    private LiveOrderAuthorization permit(LiveMutationKind kind, String commandSha256,
                                          long issuedAtMs, long expiresAtMs,
                                          LiveGovernanceDecision decision) {
        return new LiveOrderAuthorization(UUID.randomUUID().toString(), kind, commandSha256,
                issuedAtMs, expiresAtMs, decision);
    }

    private void requirePermit(LiveOrderAuthorization authorization, String commandSha256) {
        long now = clock.millis();
        if (authorization == null || authorization.consumed()
                || !authorization.commandSha256().equals(commandSha256)
                || now < authorization.issuedAtMs() || now >= authorization.expiresAtMs()) {
            throw new LiveAuthorizationException("Venue mutation permit is missing, expired or mismatched");
        }
    }

    private void requireSafeMutationEnabled(Exchange exchange) {
        properties.validateStartupPolicy();
        if (properties.getOperatingMode() == PrivateTradingProperties.OperatingMode.RESEARCH_ONLY) {
            throw new LiveAuthorizationException("Venue writes are forbidden in RESEARCH_ONLY mode");
        }
        requireSandboxEndpoint(exchange);
        requireVenueEnabled(exchange);
    }

    private void requireLiveOpeningStatic(Exchange exchange) {
        requireOrderPreflight(exchange);
        if (properties.getOperatingMode() != PrivateTradingProperties.OperatingMode.LIVE_CANARY) {
            throw new LiveAuthorizationException("LIVE risk authorization requires LIVE_CANARY mode");
        }
        text(properties.getTargetJurisdiction(), "target jurisdiction");
        text(properties.getLegalApprovalReference(), "legal approval reference");
        requireOpeningFlags(exchange);
    }

    private void requireOpeningFlags(Exchange exchange) {
        boolean venueWriteEnabled = switch (exchange) {
            case BINANCE -> properties.getBinance().isWriteEnabled();
            case OKX -> properties.getOkx().isWriteEnabled();
            case COINGLASS -> false;
        };
        if (!properties.isLiveWriteEnabled() || !venueWriteEnabled) {
            throw new LiveAuthorizationException("Risk-increasing venue writes are disabled for " + exchange);
        }
    }

    /** The final mutation boundary must observe the durable/local kill-switch health itself. */
    private void requireOpeningKillSwitch() {
        // For a Spring-managed switch this performs a synchronous durable read. The compatibility
        // in-memory switch used by isolated tests treats the call as a no-op.
        killSwitch.refreshFromStore();
        if (!killSwitch.isDurableStateRestored() || !killSwitch.isPersistenceHealthy()
                || killSwitch.getMode() != KillSwitch.Mode.NORMAL) {
            throw new LiveAuthorizationException(
                    "Risk-increasing venue writes require a healthy durable NORMAL kill switch");
        }
    }

    private void requireVenueEnabled(Exchange exchange) {
        boolean enabled = switch (exchange) {
            case BINANCE -> properties.getBinance().isEnabled();
            case OKX -> properties.getOkx().isEnabled();
            case COINGLASS -> false;
        };
        if (!enabled) throw new LiveAuthorizationException("Private venue is disabled for " + exchange);
    }

    private void requireSandboxEndpoint(Exchange exchange) {
        if (properties.getOperatingMode() != PrivateTradingProperties.OperatingMode.SANDBOX) return;
        boolean sandbox = switch (exchange) {
            case BINANCE -> properties.getBinance().isTestnet();
            case OKX -> properties.getOkx().isSimulatedTrading();
            case COINGLASS -> false;
        };
        if (!sandbox) {
            throw new LiveAuthorizationException(
                    "SANDBOX writes require a venue testnet/demo endpoint for " + exchange);
        }
    }

    private String configuredAccountId(Exchange exchange) {
        return switch (exchange) {
            case BINANCE -> properties.getBinance().getAccountId();
            case OKX -> properties.getOkx().getAccountId();
            case COINGLASS -> "";
        };
    }

    private String configuredKeyReference(Exchange exchange) {
        return switch (exchange) {
            case BINANCE -> properties.getBinance().getKeyReference();
            case OKX -> properties.getOkx().getKeyReference();
            case COINGLASS -> "";
        };
    }

    private String canonical(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT)
                .replace("-", "").replace("_", "").replace("/", "");
    }

    private String text(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new LiveAuthorizationException(name + " is NOT_VERIFIED");
        return normalized;
    }
}
