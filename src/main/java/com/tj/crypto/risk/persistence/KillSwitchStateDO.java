package com.tj.crypto.risk.persistence;

import lombok.Data;

/** MyBatis projection for the singleton kill_switch_state row. */
@Data
public class KillSwitchStateDO {
    private String stateKey;
    private String mode;
    private String reason;
    private String changedBy;
    private Long changedAtMs;
    private Long stateVersion;
}
