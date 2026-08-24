package com.tj.crypto.marketdata.archive;

/** 实时连接器到 raw-first journal 的接线状态。 */
public enum RealtimeCaptureCapability {
    /** journal/compactor 已实现，但 Binance/OKX/Coinglass 连接器尚未逐一接入，保持 NO-GO。 */
    NOT_WIRED,
    WIRED
}
