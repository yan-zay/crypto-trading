package com.tj.crypto.autonomy.agent;

/** L4 只接受 COMPLETE；UNKNOWN/INCOMPLETE 显式 fail-closed。 */
public enum RiskSnapshotCompleteness {
    COMPLETE,
    INCOMPLETE,
    UNKNOWN
}
