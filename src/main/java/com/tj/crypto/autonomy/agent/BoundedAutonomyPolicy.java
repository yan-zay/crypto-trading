package com.tj.crypto.autonomy.agent;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 人类预先批准的不可变 L4 边界。策略、账户、venue、标的组合及所有资本限制均为精确值。
 */
public record BoundedAutonomyPolicy(
        String policyId,
        String policyVersion,
        String policyArtifactSha256,
        AutonomyGovernanceContext governanceContext,
        Set<AutonomyScope> allowedScopes,
        Set<TradeProposalAction> allowedActions,
        List<TradingWindow> tradingWindows,
        BigDecimal maxNotional,
        String settlementCurrency,
        BigDecimal maxDailyLoss,
        BigDecimal maxLossAtStop,
        Duration maxProposalTtl,
        Duration maxCapabilityTtl,
        Duration maxLossSnapshotAge,
        Instant validFrom,
        Instant validUntil) {

    private static final String HASH_DOMAIN = "bounded-autonomy-policy-v1";
    public static final Duration ABSOLUTE_MAX_PROPOSAL_TTL = Duration.ofMinutes(15);
    public static final Duration ABSOLUTE_MAX_CAPABILITY_TTL = Duration.ofMinutes(2);
    public static final Duration ABSOLUTE_MAX_LOSS_SNAPSHOT_AGE = Duration.ofMinutes(1);
    private static final Set<TradeProposalAction> L4_ACTIONS = Set.of(
            TradeProposalAction.L4_SELECT_PREAPPROVED,
            TradeProposalAction.L4_ADJUST_PREAPPROVED);

    public BoundedAutonomyPolicy {
        policyId = AutonomyValues.text(policyId, "policyId");
        policyVersion = AutonomyValues.text(policyVersion, "policyVersion");
        policyArtifactSha256 = AutonomyValues.digest(policyArtifactSha256, "policyArtifactSha256");
        if (governanceContext == null) throw new IllegalArgumentException("governanceContext is required");
        if (allowedScopes == null || allowedScopes.isEmpty()
                || allowedActions == null || allowedActions.isEmpty()
                || tradingWindows == null || tradingWindows.isEmpty()) {
            throw new IllegalArgumentException("policy scopes, actions and trading windows are required");
        }
        allowedScopes = Set.copyOf(allowedScopes);
        allowedActions = Set.copyOf(allowedActions);
        if (!L4_ACTIONS.containsAll(allowedActions)) {
            throw new IllegalArgumentException("L4 policy can only permit preapproved select/adjust actions");
        }
        tradingWindows = List.copyOf(tradingWindows);
        maxNotional = AutonomyValues.positiveDecimal(maxNotional, "maxNotional");
        settlementCurrency = AutonomyValues.text(settlementCurrency, "settlementCurrency").toUpperCase(Locale.ROOT);
        maxDailyLoss = AutonomyValues.positiveDecimal(maxDailyLoss, "maxDailyLoss");
        maxLossAtStop = AutonomyValues.positiveDecimal(maxLossAtStop, "maxLossAtStop");
        requirePositive(maxProposalTtl, "maxProposalTtl");
        requirePositive(maxCapabilityTtl, "maxCapabilityTtl");
        requirePositive(maxLossSnapshotAge, "maxLossSnapshotAge");
        requireAtMost(maxProposalTtl, ABSOLUTE_MAX_PROPOSAL_TTL, "maxProposalTtl");
        requireAtMost(maxCapabilityTtl, ABSOLUTE_MAX_CAPABILITY_TTL, "maxCapabilityTtl");
        requireAtMost(maxLossSnapshotAge, ABSOLUTE_MAX_LOSS_SNAPSHOT_AGE, "maxLossSnapshotAge");
        if (validFrom == null || validUntil == null || !validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("policy validity window must be positive");
        }
        String expectedDigest = hash(policyId, policyVersion, governanceContext, allowedScopes, allowedActions,
                tradingWindows, maxNotional, settlementCurrency, maxDailyLoss, maxLossAtStop,
                maxProposalTtl, maxCapabilityTtl, maxLossSnapshotAge, validFrom, validUntil);
        if (!expectedDigest.equals(policyArtifactSha256)) {
            throw new IllegalArgumentException(
                    "policyArtifactSha256 must address the complete normalized policy content");
        }
    }

    /** 创建内容寻址政策；外部政策权威必须对返回的 {@code policyArtifactSha256} 签名。 */
    public static BoundedAutonomyPolicy create(
            String policyId,
            String policyVersion,
            AutonomyGovernanceContext governanceContext,
            Set<AutonomyScope> allowedScopes,
            Set<TradeProposalAction> allowedActions,
            List<TradingWindow> tradingWindows,
            BigDecimal maxNotional,
            String settlementCurrency,
            BigDecimal maxDailyLoss,
            BigDecimal maxLossAtStop,
            Duration maxProposalTtl,
            Duration maxCapabilityTtl,
            Duration maxLossSnapshotAge,
            Instant validFrom,
            Instant validUntil) {
        String id = AutonomyValues.text(policyId, "policyId");
        String version = AutonomyValues.text(policyVersion, "policyVersion");
        if (governanceContext == null) throw new IllegalArgumentException("governanceContext is required");
        if (allowedScopes == null || allowedScopes.isEmpty()
                || allowedActions == null || allowedActions.isEmpty()
                || tradingWindows == null || tradingWindows.isEmpty()) {
            throw new IllegalArgumentException("policy scopes, actions and trading windows are required");
        }
        Set<AutonomyScope> scopes = Set.copyOf(allowedScopes);
        Set<TradeProposalAction> actions = Set.copyOf(allowedActions);
        List<TradingWindow> windows = List.copyOf(tradingWindows);
        BigDecimal notional = AutonomyValues.positiveDecimal(maxNotional, "maxNotional");
        String currency = AutonomyValues.text(settlementCurrency, "settlementCurrency").toUpperCase(Locale.ROOT);
        BigDecimal dailyLoss = AutonomyValues.positiveDecimal(maxDailyLoss, "maxDailyLoss");
        BigDecimal lossAtStop = AutonomyValues.positiveDecimal(maxLossAtStop, "maxLossAtStop");
        requirePositive(maxProposalTtl, "maxProposalTtl");
        requirePositive(maxCapabilityTtl, "maxCapabilityTtl");
        requirePositive(maxLossSnapshotAge, "maxLossSnapshotAge");
        requireAtMost(maxProposalTtl, ABSOLUTE_MAX_PROPOSAL_TTL, "maxProposalTtl");
        requireAtMost(maxCapabilityTtl, ABSOLUTE_MAX_CAPABILITY_TTL, "maxCapabilityTtl");
        requireAtMost(maxLossSnapshotAge, ABSOLUTE_MAX_LOSS_SNAPSHOT_AGE, "maxLossSnapshotAge");
        if (validFrom == null || validUntil == null || !validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("policy validity window must be positive");
        }
        String digest = hash(id, version, governanceContext, scopes, actions, windows, notional, currency,
                dailyLoss, lossAtStop, maxProposalTtl, maxCapabilityTtl, maxLossSnapshotAge,
                validFrom, validUntil);
        return new BoundedAutonomyPolicy(id, version, digest, governanceContext, scopes, actions, windows,
                notional, currency, dailyLoss, lossAtStop, maxProposalTtl, maxCapabilityTtl,
                maxLossSnapshotAge, validFrom, validUntil);
    }

    /** 执行全部确定性 L4 边界；任何未知、陈旧或超限事实默认拒绝。 */
    public void requireAllows(TradeProposal proposal, DailyLossSnapshot lossSnapshot, Instant now) {
        if (proposal == null || lossSnapshot == null || now == null) {
            throw new IllegalArgumentException("proposal, lossSnapshot and now are required");
        }
        if (!policyId.equals(proposal.policyId())
                || !policyVersion.equals(proposal.policyVersion())
                || !policyArtifactSha256.equals(proposal.policyArtifactSha256())) {
            throw violation("L4_POLICY_MISMATCH", "Proposal does not bind the exact policy artifact");
        }
        if (!governanceContext.equals(proposal.governanceContext())) {
            throw violation("L4_GOVERNANCE_CONTEXT_MISMATCH",
                    "Proposal environment or governance subject is outside this policy");
        }
        if (now.isBefore(validFrom) || !now.isBefore(validUntil)
                || proposal.createdAt().isBefore(validFrom) || proposal.expiresAt().isAfter(validUntil)) {
            throw violation("L4_POLICY_INACTIVE", "Policy or proposal is outside the approved validity period");
        }
        if (!allowedActions.contains(proposal.action())) {
            throw violation("L4_ACTION_NOT_ALLOWED", "Proposal action is not preapproved");
        }
        if (!allowedScopes.contains(AutonomyScope.from(proposal))) {
            throw violation("L4_SCOPE_NOT_ALLOWED", "Strategy/account/venue/instrument scope is not preapproved");
        }
        if (tradingWindows.stream().noneMatch(window ->
                window.containsInterval(proposal.createdAt(), proposal.expiresAt()))) {
            throw violation("L4_TRADING_WINDOW_NOT_ALLOWED", "Complete proposal TTL is outside approved hours");
        }
        if (!settlementCurrency.equals(proposal.settlementCurrency())) {
            throw violation("L4_CURRENCY_MISMATCH", "Proposal settlement currency does not match policy");
        }
        if (proposal.proposedNotional().compareTo(maxNotional) > 0) {
            throw violation("L4_MAX_NOTIONAL_EXCEEDED", "Proposal exceeds the hard notional ceiling");
        }
        if (proposal.lossAtStop().compareTo(maxLossAtStop) > 0) {
            throw violation("L4_LOSS_AT_STOP_EXCEEDED", "Proposal exceeds approved loss-at-stop");
        }
        if (Duration.between(proposal.createdAt(), proposal.expiresAt()).compareTo(maxProposalTtl) > 0
                || proposal.createdAt().isAfter(now) || !now.isBefore(proposal.expiresAt())) {
            throw violation("L4_PROPOSAL_TTL_INVALID", "Proposal is future-dated, expired or exceeds policy TTL");
        }
        if (!lossSnapshot.account().equals(proposal.account())
                || !lossSnapshot.settlementCurrency().equals(settlementCurrency)) {
            throw violation("L4_DAILY_LOSS_SCOPE_MISMATCH", "Daily-loss snapshot scope does not match proposal");
        }
        if (lossSnapshot.completeness() != RiskSnapshotCompleteness.COMPLETE) {
            throw violation("L4_DAILY_LOSS_INCOMPLETE", "Daily-loss and pending-risk facts are not complete");
        }
        if (!lossSnapshot.asOf().atZone(lossSnapshot.tradingDayZone()).toLocalDate()
                .equals(lossSnapshot.tradingDay())
                || !now.atZone(lossSnapshot.tradingDayZone()).toLocalDate()
                .equals(lossSnapshot.tradingDay())) {
            throw violation("L4_TRADING_DAY_MISMATCH", "Risk snapshot does not cover the current trading day");
        }
        if (lossSnapshot.asOf().isAfter(now)
                || Duration.between(lossSnapshot.asOf(), now).compareTo(maxLossSnapshotAge) > 0) {
            throw violation("L4_DAILY_LOSS_STALE", "Daily-loss snapshot is future-dated or stale");
        }
        if (lossSnapshot.pendingReservedNotional().add(proposal.proposedNotional())
                .compareTo(maxNotional) > 0) {
            throw violation("L4_CUMULATIVE_NOTIONAL_EXCEEDED",
                    "Pending reservations plus proposal exceed the policy notional ceiling");
        }
        if (lossSnapshot.realizedLoss().add(lossSnapshot.pendingReservedLoss())
                .add(proposal.lossAtStop()).compareTo(maxDailyLoss) > 0) {
            throw violation("L4_DAILY_LOSS_EXCEEDED", "Realized loss plus loss-at-stop exceeds daily limit");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireAtMost(Duration value, Duration ceiling, String name) {
        if (value.compareTo(ceiling) > 0) {
            throw new IllegalArgumentException(name + " exceeds the absolute safety ceiling " + ceiling);
        }
    }

    private static String hash(
            String policyId,
            String policyVersion,
            AutonomyGovernanceContext governanceContext,
            Set<AutonomyScope> allowedScopes,
            Set<TradeProposalAction> allowedActions,
            List<TradingWindow> tradingWindows,
            BigDecimal maxNotional,
            String settlementCurrency,
            BigDecimal maxDailyLoss,
            BigDecimal maxLossAtStop,
            Duration maxProposalTtl,
            Duration maxCapabilityTtl,
            Duration maxLossSnapshotAge,
            Instant validFrom,
            Instant validUntil) {
        return AutonomyValues.sha256(sink -> {
            sink.value(HASH_DOMAIN)
                    .value(policyId)
                    .value(policyVersion)
                    .value(governanceContext.deploymentEnvironment())
                    .value(governanceContext.governanceSubjectSha256())
                    .value(Integer.toString(allowedScopes.size()));
            allowedScopes.stream()
                    .sorted(Comparator.comparing(BoundedAutonomyPolicy::scopeSortKey))
                    .forEach(scope -> sink
                            .value(scope.strategy().strategyId())
                            .value(scope.strategy().releaseVersion())
                            .value(scope.strategy().strategyArtifactSha256())
                            .value(scope.strategy().runtimeAssignmentId())
                            .value(scope.strategy().runtimeAssignmentVersion())
                            .value(scope.account().accountId())
                            .value(scope.venue().venueId())
                            .value(scope.instrument().instrumentNamespace())
                            .value(scope.instrument().assetClass())
                            .value(scope.instrument().instrumentId())
                            .value(scope.instrument().instrumentSpecificationSha256()));
            sink.value(Integer.toString(allowedActions.size()));
            allowedActions.stream().map(Enum::name).sorted().forEach(sink::value);
            sink.value(Integer.toString(tradingWindows.size()));
            tradingWindows.stream()
                    .sorted(Comparator.comparing(BoundedAutonomyPolicy::windowSortKey))
                    .forEach(window -> sink
                            .value(window.startDay().name())
                            .value(window.startInclusive().toString())
                            .value(window.endExclusive().toString())
                            .value(window.zoneId().getId()));
            sink.value(maxNotional)
                    .value(settlementCurrency)
                    .value(maxDailyLoss)
                    .value(maxLossAtStop)
                    .value(maxProposalTtl.toString())
                    .value(maxCapabilityTtl.toString())
                    .value(maxLossSnapshotAge.toString())
                    .value(validFrom)
                    .value(validUntil);
        });
    }

    private static String scopeSortKey(AutonomyScope scope) {
        return String.join("\u0000", scope.strategy().strategyId(), scope.strategy().releaseVersion(),
                scope.strategy().strategyArtifactSha256(), scope.strategy().runtimeAssignmentId(),
                scope.strategy().runtimeAssignmentVersion(),
                scope.account().accountId(), scope.venue().venueId(),
                scope.instrument().instrumentNamespace(), scope.instrument().assetClass(),
                scope.instrument().instrumentId(), scope.instrument().instrumentSpecificationSha256());
    }

    private static String windowSortKey(TradingWindow window) {
        return String.join("\u0000", window.startDay().name(), window.startInclusive().toString(),
                window.endExclusive().toString(), window.zoneId().getId());
    }

    private static AgentAutonomyViolationException violation(String code, String message) {
        return new AgentAutonomyViolationException(code, message);
    }
}
