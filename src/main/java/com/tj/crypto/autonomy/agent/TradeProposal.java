package com.tj.crypto.autonomy.agent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;

/**
 * 类型化、内容寻址且不可执行的交易建议。
 *
 * <p>本对象没有数量换算、签名、gateway、OMS 或订单发送能力。{@code proposalId} 由完整规范化负载
 * 的 SHA-256 派生；任何字段变化都会产生不同身份。
 */
public record TradeProposal(
        String proposalId,
        String proposalSha256,
        TradeProposalAction action,
        AutonomyGovernanceContext governanceContext,
        String agentIdentity,
        AgentDecisionLineage agentLineage,
        StrategyRef strategy,
        AccountRef account,
        VenueRef venue,
        InstrumentRef instrument,
        TradeDirection direction,
        BigDecimal proposedNotional,
        String settlementCurrency,
        BigDecimal lossAtStop,
        String policyId,
        String policyVersion,
        String policyArtifactSha256,
        String rationaleSha256,
        Instant createdAt,
        Instant expiresAt) {

    private static final String HASH_DOMAIN = "trade-proposal-v1";

    public TradeProposal {
        proposalSha256 = AutonomyValues.digest(proposalSha256, "proposalSha256");
        proposalId = AutonomyValues.text(proposalId, "proposalId");
        if (action == null || governanceContext == null || agentLineage == null
                || strategy == null || account == null || venue == null
                || instrument == null || direction == null) {
            throw new IllegalArgumentException("action, scope and direction are required");
        }
        agentIdentity = AutonomyValues.text(agentIdentity, "agentIdentity");
        proposedNotional = AutonomyValues.positiveDecimal(proposedNotional, "proposedNotional");
        settlementCurrency = AutonomyValues.text(settlementCurrency, "settlementCurrency").toUpperCase(Locale.ROOT);
        lossAtStop = AutonomyValues.nonNegativeDecimal(lossAtStop, "lossAtStop");
        policyId = AutonomyValues.text(policyId, "policyId");
        policyVersion = AutonomyValues.text(policyVersion, "policyVersion");
        policyArtifactSha256 = AutonomyValues.digest(policyArtifactSha256, "policyArtifactSha256");
        rationaleSha256 = AutonomyValues.digest(rationaleSha256, "rationaleSha256");
        if (createdAt == null || expiresAt == null || !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("proposal timestamps must define a positive validity window");
        }
        String expectedDigest = hash(action, governanceContext, agentIdentity, agentLineage,
                strategy, account, venue, instrument,
                direction, proposedNotional, settlementCurrency, lossAtStop, policyId, policyVersion,
                policyArtifactSha256, rationaleSha256, createdAt, expiresAt);
        if (!expectedDigest.equals(proposalSha256) || !expectedDigest.equals(proposalId)) {
            throw new IllegalArgumentException("proposalId and proposalSha256 must address the complete payload");
        }
    }

    public static TradeProposal create(
            TradeProposalAction action,
            AutonomyGovernanceContext governanceContext,
            String agentIdentity,
            AgentDecisionLineage agentLineage,
            StrategyRef strategy,
            AccountRef account,
            VenueRef venue,
            InstrumentRef instrument,
            TradeDirection direction,
            BigDecimal proposedNotional,
            String settlementCurrency,
            BigDecimal lossAtStop,
            String policyId,
            String policyVersion,
            String policyArtifactSha256,
            String rationaleSha256,
            Instant createdAt,
            Instant expiresAt) {
        String normalizedAgent = AutonomyValues.text(agentIdentity, "agentIdentity");
        BigDecimal normalizedNotional = AutonomyValues.positiveDecimal(proposedNotional, "proposedNotional");
        String normalizedCurrency = AutonomyValues.text(settlementCurrency, "settlementCurrency").toUpperCase(Locale.ROOT);
        BigDecimal normalizedLoss = AutonomyValues.nonNegativeDecimal(lossAtStop, "lossAtStop");
        String normalizedPolicyId = AutonomyValues.text(policyId, "policyId");
        String normalizedPolicyVersion = AutonomyValues.text(policyVersion, "policyVersion");
        String normalizedPolicyDigest = AutonomyValues.digest(policyArtifactSha256, "policyArtifactSha256");
        String normalizedRationale = AutonomyValues.digest(rationaleSha256, "rationaleSha256");
        String digest = hash(action, governanceContext, normalizedAgent, agentLineage,
                strategy, account, venue, instrument, direction,
                normalizedNotional, normalizedCurrency, normalizedLoss, normalizedPolicyId,
                normalizedPolicyVersion, normalizedPolicyDigest, normalizedRationale, createdAt, expiresAt);
        return new TradeProposal(digest, digest, action, governanceContext, normalizedAgent,
                agentLineage, strategy, account,
                venue, instrument, direction, normalizedNotional, normalizedCurrency, normalizedLoss,
                normalizedPolicyId, normalizedPolicyVersion, normalizedPolicyDigest, normalizedRationale,
                createdAt, expiresAt);
    }

    private static String hash(
            TradeProposalAction action,
            AutonomyGovernanceContext governanceContext,
            String agentIdentity,
            AgentDecisionLineage agentLineage,
            StrategyRef strategy,
            AccountRef account,
            VenueRef venue,
            InstrumentRef instrument,
            TradeDirection direction,
            BigDecimal proposedNotional,
            String settlementCurrency,
            BigDecimal lossAtStop,
            String policyId,
            String policyVersion,
            String policyArtifactSha256,
            String rationaleSha256,
            Instant createdAt,
            Instant expiresAt) {
        if (action == null || governanceContext == null || agentLineage == null
                || strategy == null || account == null || venue == null
                || instrument == null || direction == null || createdAt == null || expiresAt == null) {
            throw new IllegalArgumentException("complete proposal payload is required");
        }
        return AutonomyValues.sha256(sink -> sink
                .value(HASH_DOMAIN)
                .value(action.name())
                .value(governanceContext.deploymentEnvironment())
                .value(governanceContext.governanceSubjectSha256())
                .value(AutonomyValues.text(agentIdentity, "agentIdentity"))
                .value(agentLineage.modelVersion())
                .value(agentLineage.promptSha256())
                .value(agentLineage.contextSha256())
                .value(agentLineage.toolPolicyVersion())
                .value(agentLineage.traceRootSha256())
                .value(agentLineage.evalReportSha256())
                .value(agentLineage.dataManifestSha256())
                .value(strategy.strategyId())
                .value(strategy.releaseVersion())
                .value(strategy.strategyArtifactSha256())
                .value(strategy.runtimeAssignmentId())
                .value(strategy.runtimeAssignmentVersion())
                .value(account.accountId())
                .value(venue.venueId())
                .value(instrument.instrumentNamespace())
                .value(instrument.assetClass())
                .value(instrument.instrumentId())
                .value(instrument.instrumentSpecificationSha256())
                .value(direction.name())
                .value(AutonomyValues.positiveDecimal(proposedNotional, "proposedNotional"))
                .value(AutonomyValues.text(settlementCurrency, "settlementCurrency").toUpperCase(Locale.ROOT))
                .value(AutonomyValues.nonNegativeDecimal(lossAtStop, "lossAtStop"))
                .value(AutonomyValues.text(policyId, "policyId"))
                .value(AutonomyValues.text(policyVersion, "policyVersion"))
                .value(AutonomyValues.digest(policyArtifactSha256, "policyArtifactSha256"))
                .value(AutonomyValues.digest(rationaleSha256, "rationaleSha256"))
                .value(createdAt)
                .value(expiresAt));
    }
}
