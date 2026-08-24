package com.tj.crypto.governance.evidence;

/** External controls whose real evidence is required before capital can be put at risk. */
public enum ControlKind {
    LEGAL_APPROVAL,
    KMS_KEY_CUSTODY,
    MFA,
    DUAL_APPROVAL,
    IP_ALLOWLIST,
    EXTERNAL_ALERT_DELIVERY,
    BACKUP_PITR_RESTORE,
    RPO_RTO,
    VENUE_DEMO_CONTRACT,
    PRIVATE_STREAM_STABILITY,
    SOAK_24H,
    SOAK_7D,
    CHAOS,
    FORWARD_PAPER_30D
}
