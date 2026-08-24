package com.tj.crypto.autonomy.operations;

/**
 * Declarative action only. The watchdog deliberately has no KillSwitch, OMS or venue dependency.
 */
public enum OperationsActionType {
    HOLD_RISK_INCREASE,
    ALLOW_VERIFIED_RISK_REDUCTION,
    REQUEST_KILL_SWITCH_HALT,
    REQUEST_RECONCILIATION,
    REQUEST_DUAL_CONTROL,
    ALERT_HUMAN_OPERATOR,
    DISABLE_AGENT_ACTIONS,
    HAND_CONTROL_TO_OPERATOR,
    CONTINUE_BOUNDED_OPERATION
}
