package com.tj.crypto.admin.domain;

import lombok.Data;

/** Row-locked cursor used to serialize the tamper-evident audit hash chain. */
@Data
public class AuditChainHeadDO {
    private String chainName;
    private Long lastAuditId;
    private String lastHash;
    private Long version;
}
