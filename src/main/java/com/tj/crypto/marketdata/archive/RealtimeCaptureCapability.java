package com.tj.crypto.marketdata.archive;

/** 实时连接器到 raw-first journal 的接线状态。 */
public enum RealtimeCaptureCapability {
    /** Journal exists but the owning connector assembly has not proven raw-before-canonical capture. */
    NOT_WIRED,
    WIRED
}
