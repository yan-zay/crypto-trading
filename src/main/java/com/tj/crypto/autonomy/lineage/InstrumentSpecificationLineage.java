package com.tj.crypto.autonomy.lineage;

/** Asset-class-neutral instrument identity and immutable interpretation rules. */
public record InstrumentSpecificationLineage(
        String instrumentNamespace,
        String assetClass,
        String instrumentId,
        String specificationVersion,
        String instrumentSpecificationSha256) {

    public InstrumentSpecificationLineage {
        instrumentNamespace = OrderDecisionLineageValues.text(
                instrumentNamespace, "instrumentNamespace");
        assetClass = OrderDecisionLineageValues.upper(assetClass, "assetClass");
        instrumentId = OrderDecisionLineageValues.text(instrumentId, "instrumentId");
        specificationVersion = OrderDecisionLineageValues.text(
                specificationVersion, "specificationVersion");
        instrumentSpecificationSha256 = OrderDecisionLineageValues.digest(
                instrumentSpecificationSha256, "instrumentSpecificationSha256");
    }
}
