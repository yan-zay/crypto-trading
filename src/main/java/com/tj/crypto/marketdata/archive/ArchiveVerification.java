package com.tj.crypto.marketdata.archive;

import java.nio.file.Path;

/** 经过 manifest、对象 SHA-256、Parquet 页校验和逐行身份校验后的结果。 */
public record ArchiveVerification(
        ArchiveManifest manifest,
        Path manifestPath,
        Path dataPath,
        String manifestSha256,
        long verifiedRows
) {}
