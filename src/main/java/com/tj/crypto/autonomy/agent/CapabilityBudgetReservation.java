package com.tj.crypto.autonomy.agent;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * L4 capability 签发时必须原子落库的累计预算预占请求。
 *
 * <p>持久 store 必须以数据库时间和 risk snapshot head 做 CAS，并将该预占、token 与审计事实
 * 放入同一事务；自报数值不能替代确定性估值和 OMS 风控事实。
 */
public record CapabilityBudgetReservation(
        String reservationSha256,
        String proposalId,
        String proposalSha256,
        String policyId,
        String policyVersion,
        String policyArtifactSha256,
        String riskSnapshotId,
        String riskSnapshotSha256,
        AccountRef account,
        String settlementCurrency,
        BigDecimal expectedRealizedLoss,
        BigDecimal expectedPendingNotional,
        BigDecimal expectedPendingLoss,
        BigDecimal notionalToReserve,
        BigDecimal lossAtStopToReserve,
        BigDecimal maxNotional,
        BigDecimal maxDailyLoss,
        Instant createdAt,
        Instant expiresAt) {

    private static final String HASH_DOMAIN = "agent-l4-capability-budget-reservation-v1";

    public CapabilityBudgetReservation {
        reservationSha256 = AutonomyValues.digest(reservationSha256, "reservationSha256");
        proposalId = AutonomyValues.text(proposalId, "proposalId");
        proposalSha256 = AutonomyValues.digest(proposalSha256, "proposalSha256");
        policyId = AutonomyValues.text(policyId, "policyId");
        policyVersion = AutonomyValues.text(policyVersion, "policyVersion");
        policyArtifactSha256 = AutonomyValues.digest(policyArtifactSha256, "policyArtifactSha256");
        riskSnapshotId = AutonomyValues.text(riskSnapshotId, "riskSnapshotId");
        riskSnapshotSha256 = AutonomyValues.digest(riskSnapshotSha256, "riskSnapshotSha256");
        if (account == null) throw new IllegalArgumentException("account is required");
        settlementCurrency = AutonomyValues.text(
                settlementCurrency, "settlementCurrency").toUpperCase(java.util.Locale.ROOT);
        expectedRealizedLoss = AutonomyValues.nonNegativeDecimal(
                expectedRealizedLoss, "expectedRealizedLoss");
        expectedPendingNotional = AutonomyValues.nonNegativeDecimal(
                expectedPendingNotional, "expectedPendingNotional");
        expectedPendingLoss = AutonomyValues.nonNegativeDecimal(
                expectedPendingLoss, "expectedPendingLoss");
        notionalToReserve = AutonomyValues.positiveDecimal(notionalToReserve, "notionalToReserve");
        lossAtStopToReserve = AutonomyValues.nonNegativeDecimal(
                lossAtStopToReserve, "lossAtStopToReserve");
        maxNotional = AutonomyValues.positiveDecimal(maxNotional, "maxNotional");
        maxDailyLoss = AutonomyValues.positiveDecimal(maxDailyLoss, "maxDailyLoss");
        if (createdAt == null || expiresAt == null || !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("reservation lifetime must be positive");
        }
        String expected = hash(proposalId, proposalSha256, policyId, policyVersion,
                policyArtifactSha256, riskSnapshotId, riskSnapshotSha256, account,
                settlementCurrency, expectedRealizedLoss, expectedPendingNotional,
                expectedPendingLoss, notionalToReserve, lossAtStopToReserve,
                maxNotional, maxDailyLoss, createdAt, expiresAt);
        if (!expected.equals(reservationSha256)) {
            throw new IllegalArgumentException("reservationSha256 must bind the complete reservation");
        }
    }

    static CapabilityBudgetReservation create(
            TradeProposal proposal,
            DailyLossSnapshot snapshot,
            BoundedAutonomyPolicy policy,
            Instant createdAt,
            Instant expiresAt) {
        String digest = hash(proposal.proposalId(), proposal.proposalSha256(), proposal.policyId(),
                proposal.policyVersion(), proposal.policyArtifactSha256(), snapshot.snapshotId(),
                snapshot.snapshotSha256(), proposal.account(), proposal.settlementCurrency(),
                snapshot.realizedLoss(), snapshot.pendingReservedNotional(),
                snapshot.pendingReservedLoss(), proposal.proposedNotional(), proposal.lossAtStop(),
                policy.maxNotional(), policy.maxDailyLoss(), createdAt, expiresAt);
        return new CapabilityBudgetReservation(digest, proposal.proposalId(), proposal.proposalSha256(),
                proposal.policyId(), proposal.policyVersion(), proposal.policyArtifactSha256(),
                snapshot.snapshotId(), snapshot.snapshotSha256(), proposal.account(),
                proposal.settlementCurrency(), snapshot.realizedLoss(),
                snapshot.pendingReservedNotional(), snapshot.pendingReservedLoss(),
                proposal.proposedNotional(), proposal.lossAtStop(), policy.maxNotional(),
                policy.maxDailyLoss(), createdAt, expiresAt);
    }

    private static String hash(
            String proposalId,
            String proposalSha256,
            String policyId,
            String policyVersion,
            String policyArtifactSha256,
            String riskSnapshotId,
            String riskSnapshotSha256,
            AccountRef account,
            String currency,
            BigDecimal expectedRealizedLoss,
            BigDecimal expectedPendingNotional,
            BigDecimal expectedPendingLoss,
            BigDecimal notional,
            BigDecimal lossAtStop,
            BigDecimal maxNotional,
            BigDecimal maxDailyLoss,
            Instant createdAt,
            Instant expiresAt) {
        return AutonomyValues.sha256(sink -> sink
                .value(HASH_DOMAIN)
                .value(proposalId)
                .value(proposalSha256)
                .value(policyId)
                .value(policyVersion)
                .value(policyArtifactSha256)
                .value(riskSnapshotId)
                .value(riskSnapshotSha256)
                .value(account.accountId())
                .value(currency)
                .value(expectedRealizedLoss)
                .value(expectedPendingNotional)
                .value(expectedPendingLoss)
                .value(notional)
                .value(lossAtStop)
                .value(maxNotional)
                .value(maxDailyLoss)
                .value(createdAt)
                .value(expiresAt));
    }
}
