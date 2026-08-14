package com.tj.crypto.observability.slo;

import lombok.Data;

import java.math.BigDecimal;

/** Persisted SLO window for audit, trend charts and incident review. */
@Data
public class SloSnapshotDO {
    private String snapshotId;
    private String sloName;
    private Long windowStartMs;
    private Long windowEndMs;
    private BigDecimal targetValue;
    private BigDecimal actualValue;
    private Boolean compliant;
    private BigDecimal errorBudgetRemainingPct;
    private Long sampleCount;
    private String detailJson;
}
