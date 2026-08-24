package com.tj.crypto.autonomy.agent;

import java.util.Locale;

/**
 * 资产类别无关且版本化的标的引用。
 *
 * <p>specification 摘要必须覆盖 quantity unit、valuation、contract terms 等确定性解释规则，
 * 防止相同 symbol 在不同资产或合约版本中被误解。
 */
public record InstrumentRef(
        String instrumentNamespace,
        String assetClass,
        String instrumentId,
        String instrumentSpecificationSha256) {
    public InstrumentRef {
        instrumentNamespace = AutonomyValues.text(instrumentNamespace, "instrumentNamespace");
        assetClass = AutonomyValues.text(assetClass, "assetClass").toUpperCase(Locale.ROOT);
        instrumentId = AutonomyValues.text(instrumentId, "instrumentId");
        instrumentSpecificationSha256 = AutonomyValues.digest(
                instrumentSpecificationSha256, "instrumentSpecificationSha256");
    }
}
