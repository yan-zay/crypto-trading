package com.tj.crypto.autonomy.agent;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

/** 内容寻址的账户日损失事实；L4 授权制品必须绑定其完整摘要。 */
public record DailyLossSnapshot(
        String snapshotId,
        String snapshotSha256,
        AccountRef account,
        String settlementCurrency,
        LocalDate tradingDay,
        ZoneId tradingDayZone,
        BigDecimal realizedLoss,
        BigDecimal pendingReservedLoss,
        BigDecimal pendingReservedNotional,
        Instant asOf,
        String sourceManifestSha256,
        RiskSnapshotCompleteness completeness) {

    private static final String HASH_DOMAIN = "agent-daily-loss-snapshot-v1";
    private static final String ID_PREFIX = "daily-loss:sha256:";

    public DailyLossSnapshot {
        snapshotId = AutonomyValues.text(snapshotId, "snapshotId");
        snapshotSha256 = AutonomyValues.digest(snapshotSha256, "snapshotSha256");
        if (account == null || tradingDay == null || tradingDayZone == null
                || asOf == null || completeness == null) {
            throw new IllegalArgumentException("account, trading-day, asOf and completeness are required");
        }
        settlementCurrency = AutonomyValues.text(settlementCurrency, "settlementCurrency").toUpperCase(Locale.ROOT);
        realizedLoss = AutonomyValues.nonNegativeDecimal(realizedLoss, "realizedLoss");
        pendingReservedLoss = AutonomyValues.nonNegativeDecimal(
                pendingReservedLoss, "pendingReservedLoss");
        pendingReservedNotional = AutonomyValues.nonNegativeDecimal(
                pendingReservedNotional, "pendingReservedNotional");
        sourceManifestSha256 = AutonomyValues.digest(sourceManifestSha256, "sourceManifestSha256");
        String expected = hash(account, settlementCurrency, tradingDay, tradingDayZone, realizedLoss,
                pendingReservedLoss, pendingReservedNotional, asOf, sourceManifestSha256, completeness);
        if (!expected.equals(snapshotSha256) || !(ID_PREFIX + expected).equals(snapshotId)) {
            throw new IllegalArgumentException("snapshotId and snapshotSha256 must address all daily-loss facts");
        }
    }

    public static DailyLossSnapshot create(
            AccountRef account,
            String settlementCurrency,
            LocalDate tradingDay,
            ZoneId tradingDayZone,
            BigDecimal realizedLoss,
            BigDecimal pendingReservedLoss,
            BigDecimal pendingReservedNotional,
            Instant asOf,
            String sourceManifestSha256,
            RiskSnapshotCompleteness completeness) {
        if (account == null || tradingDay == null || tradingDayZone == null
                || asOf == null || completeness == null) {
            throw new IllegalArgumentException("account, trading-day, asOf and completeness are required");
        }
        String currency = AutonomyValues.text(settlementCurrency, "settlementCurrency").toUpperCase(Locale.ROOT);
        BigDecimal loss = AutonomyValues.nonNegativeDecimal(realizedLoss, "realizedLoss");
        BigDecimal reservedLoss = AutonomyValues.nonNegativeDecimal(
                pendingReservedLoss, "pendingReservedLoss");
        BigDecimal reservedNotional = AutonomyValues.nonNegativeDecimal(
                pendingReservedNotional, "pendingReservedNotional");
        String source = AutonomyValues.digest(sourceManifestSha256, "sourceManifestSha256");
        String digest = hash(account, currency, tradingDay, tradingDayZone, loss, reservedLoss,
                reservedNotional, asOf, source, completeness);
        return new DailyLossSnapshot(ID_PREFIX + digest, digest, account, currency, tradingDay,
                tradingDayZone, loss, reservedLoss, reservedNotional, asOf, source, completeness);
    }

    private static String hash(AccountRef account, String currency, LocalDate tradingDay,
                               ZoneId tradingDayZone, BigDecimal loss, BigDecimal reservedLoss,
                               BigDecimal reservedNotional, Instant asOf, String sourceManifestSha256,
                               RiskSnapshotCompleteness completeness) {
        return AutonomyValues.sha256(sink -> sink
                .value(HASH_DOMAIN)
                .value(account.accountId())
                .value(currency)
                .value(tradingDay.toString())
                .value(tradingDayZone.getId())
                .value(loss)
                .value(reservedLoss)
                .value(reservedNotional)
                .value(asOf)
                .value(sourceManifestSha256)
                .value(completeness.name()));
    }
}
